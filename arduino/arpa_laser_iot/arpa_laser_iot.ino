/*
 * ARPA LÁSER IoT - Firmware v2.1 (6 Cuerdas + Efectos + Calibración + ISO/IEC 27400)
 * Arduino UNO + HC-05 (Bluetooth Classic SPP) + 6 láseres + 6 LDR + buzzer (Pin 11)
 *
 * Cableado Arduino UNO:
 *   - 6 Fotoreceptores (LDR): A0, A1, A2, A3, A4, A5 (con divisor resistivo 10 kOhm)
 *   - 6 Módulos Láser:        D4, D5, D6, D7, D8, D9
 *   - Buzzer / Minibuffer:    D11
 *   - Bluetooth HC-05:        D2 (RX Arduino <- TX HC-05), D3 (TX Arduino -> RX HC-05 vía divisor 5V->3.3V)
 *
 * Protocolo seguro (PAYLOAD*CS\n, CS = XOR hexadecimal de 2 dígitos):
 *   App -> Arpa:
 *     AUTH:<clave>               -> Autentica la sesión (máx. 3 fallos -> bloqueo 30 s)
 *     PING                       -> Keep-alive (la sesión expira a los 15 s sin tramas)
 *     N<1-6>:<freqHz>            -> Asigna una frecuencia (65..2000 Hz) a la cuerda 1..6
 *     S:<f1>,<f2>,<f3>,<f4>,<f5>,<f6> -> Asigna las 6 notas de una vez
 *     E<1-7>                     -> Efecto: 1=Arpa, 2=Piano, 3=Guitarra, 4=8-Bit, 5=Sintetizador, 6=Guitarra eléctrica, 7=Flauta
 *     CAL                        -> Recalibra automáticamente el umbral de los 6 LDR
 *     TEST                       -> Prueba de la primera nota con el efecto activo
 *   Arpa -> App:
 *     AUTH_OK | AUTH_FAIL | AUTH_LOCK | AUTH_REQ | PONG
 *     OK:N<c>:<freq> | OK:S | OK:E<n> | OK:CAL | OK:TEST | ERR:<cod>
 *     NOTA_ACTIVADA:<1-6>
 */
#include <SoftwareSerial.h>
#include <string.h>
#include <stdlib.h>

SoftwareSerial bluetooth(2, 3);  // RX en Pin 2, TX en Pin 3

// ----------------------------------------------------------------- Hardware (6 cuerdas)
const int NUM_CUERDAS = 6;
const int pinLDR[NUM_CUERDAS]   = {A0, A1, A2, A3, A4, A5};
const int pinLaser[NUM_CUERDAS] = {4, 5, 6, 7, 8, 9};
const int pinBuzzer = 11;

// Notas iniciales en Hz: Do4, Re4, Mi4, Fa4, Sol4, La4 (configurables desde la app)
int notas[NUM_CUERDAS] = {262, 294, 330, 349, 392, 440};

// Umbral dinámico individual por cada LDR (calculado automáticamente al iniciar o con CAL)
int umbralLuz[NUM_CUERDAS] = {500, 500, 500, 500, 500, 500};

// Estado para evitar repetir el envío constante por Bluetooth
bool notaActiva[NUM_CUERDAS] = {false, false, false, false, false, false};

// ----------------------------------------------------------------- Efectos de sonido (1..7)
// 1: Arpa | 2: Piano | 3: Guitarra | 4: 8-Bit | 5: Sintetizador
uint8_t efectoActual = 1;
int ultimoIndiceNota = -1;
unsigned long inicioNotaMs = 0;
unsigned long beepHasta = 0;
bool vistaPreviaEfecto = false;

// ----------------------------------------------------------------- Seguridad (ISO/IEC 27400)
const char DEVICE_KEY[] = "ArpaIoT-2026";   // Debe coincidir con la clave ingresada en la app
const uint8_t MAX_FALLOS = 3;
const unsigned long BLOQUEO_MS = 30000UL;
const unsigned long SESION_MS  = 15000UL;

bool autenticado = false;
uint8_t fallos = 0;
unsigned long bloqueadoHasta = 0;
unsigned long ultimaTrama = 0;

// ----------------------------------------------------------------- Búfer acotado
const uint8_t MAX_TRAMA = 48;
char buffer[MAX_TRAMA + 1];
uint8_t largo = 0;
bool desborde = false;

// ================================================================= Utilidades de trama
uint8_t checksum(const char *s) {
  uint8_t cs = 0;
  while (*s) cs ^= (uint8_t)*s++;
  return cs;
}

void enviar(const char *payload) {
  char cs[3];
  uint8_t c = checksum(payload);
  const char hex[] = "0123456789ABCDEF";
  cs[0] = hex[c >> 4]; cs[1] = hex[c & 0x0F]; cs[2] = '\0';
  bluetooth.print(payload);
  bluetooth.print('*');
  bluetooth.println(cs);
}

bool claveCorrecta(const char *clave) {
  size_t a = strlen(clave), b = strlen(DEVICE_KEY);
  uint8_t dif = (a == b) ? 0 : 1;
  for (size_t i = 0; i < b; i++) {
    char x = (i < a) ? clave[i] : 0;
    dif |= (uint8_t)(x ^ DEVICE_KEY[i]);
  }
  return dif == 0;
}

char *validarTrama(char *linea) {
  char *asterisco = strrchr(linea, '*');
  if (asterisco == NULL || asterisco == linea) return NULL;
  if (strlen(asterisco) != 3) return NULL;
  *asterisco = '\0';
  char *fin;
  long recibido = strtol(asterisco + 1, &fin, 16);
  if (*fin != '\0') return NULL;
  for (char *p = linea; *p; p++) if (*p < 32 || *p > 126) return NULL;
  if ((uint8_t)recibido != checksum(linea)) return NULL;
  return linea;
}

// ================================================================= Calibración automática (6 LDR)
void calibrarSensores() {
  Serial.println("--- Iniciando Calibracion (6 sensores) ---");
  Serial.println("POR FAVOR, NO TAPES LOS LASERES AHORA...");

  tone(pinBuzzer, 880, 200);
  delay(500);

  long suma[NUM_CUERDAS] = {0, 0, 0, 0, 0, 0};
  for (int m = 0; m < 10; m++) {
    for (int i = 0; i < NUM_CUERDAS; i++) {
      suma[i] += analogRead(pinLDR[i]);
    }
    delay(100);
  }

  for (int i = 0; i < NUM_CUERDAS; i++) {
    int luzPromedio = (int)(suma[i] / 10);
    // El umbral óptimo se fija un 30% por debajo de la luz directa de cada láser
    int u = (int)(luzPromedio * 0.7f);
    if (u < 30) u = 30;   // resguardo ante sensores sin láser conectado en pruebas
    umbralLuz[i] = u;

    Serial.print("LDR "); Serial.print(i + 1);
    Serial.print(" | Luz medida: "); Serial.print(luzPromedio);
    Serial.print(" | Umbral fijado en: "); Serial.println(umbralLuz[i]);
  }

  tone(pinBuzzer, 880, 100); delay(150);
  tone(pinBuzzer, 880, 100); delay(150);
  Serial.println("--- Mini Arpa Laser IoT Ready (6 cuerdas) ---");
}

// ================================================================= Motor de efectos en Buzzer
void reproducirEfecto(int indiceNota, unsigned long ahora) {
  int f = notas[indiceNota];
  if (indiceNota != ultimoIndiceNota) {
    ultimoIndiceNota = indiceNota;
    inicioNotaMs = ahora;
  }
  unsigned long transcurrido = ahora - inicioNotaMs;

  switch (efectoActual) {
    case 1: // Arpa: brillo breve una octava arriba al pulsar (25 ms) y nota pura
      tone(pinBuzzer, (transcurrido < 25) ? (f * 2) : f);
      break;

    case 2: // Piano: ataque percusivo breve y caída natural (corta a los 300 ms)
      if (transcurrido < 20) tone(pinBuzzer, (int)(f * 1.5f));
      else if (transcurrido < 300) tone(pinBuzzer, f);
      else noTone(pinBuzzer);
      break;

    case 3: { // Guitarra: ataque brillante + vibrato sutil mientras se sostiene
      if (transcurrido < 30) {
        tone(pinBuzzer, (int)(f * 1.04f));
      } else {
        int fase = (int)((transcurrido / 60) % 4);
        int delta = (fase == 1) ? (f / 50) : (fase == 3 ? -(f / 50) : 0);
        tone(pinBuzzer, f + delta);
      }
      break;
    }

    case 4: { // 8-Bit Retro: arpegio rápido tipo chiptune (fundamental -> tercera -> quinta)
      int paso = (int)((transcurrido / 45) % 4);
      int freq8bit = f;
      if (paso == 1) freq8bit = (int)(f * 1.25f);
      else if (paso == 2) freq8bit = (int)(f * 1.5f);
      else if (paso == 3) freq8bit = f * 2;
      tone(pinBuzzer, freq8bit);
      break;
    }

    case 5: { // Sintetizador: modulación rápida dual entre fundamental y octava
      int paso = (int)((transcurrido / 35) % 2);
      tone(pinBuzzer, paso == 0 ? f : (f * 2));
      break;
    }

    case 6: { // Aproximación monofónica con vibrato y octava para buzzer pasivo.
      int delta = ((transcurrido / 45) % 2 == 0) ? f / 35 : -(f / 35);
      tone(pinBuzzer, (transcurrido < 35) ? f * 2 : f + delta);
      break;
    }

    case 7: { // Aproximación de flauta mediante un vibrato suave; sigue siendo onda cuadrada.
      int fase = (transcurrido / 50) % 4;
      int delta = fase == 1 ? f / 150 : (fase == 3 ? -(f / 150) : 0);
      tone(pinBuzzer, f + delta);
      break;
    }

    default:
      tone(pinBuzzer, f);
      break;
  }
}

// ================================================================= Comandos recibidos
void procesar(char *payload) {
  unsigned long ahora = millis();
  ultimaTrama = ahora;

  if (strncmp(payload, "AUTH:", 5) == 0) {
    if (ahora < bloqueadoHasta) { enviar("AUTH_LOCK"); return; }
    if (claveCorrecta(payload + 5)) {
      autenticado = true; fallos = 0;
      enviar("AUTH_OK");
      vistaPreviaEfecto = false; tone(pinBuzzer, 1320, 80); beepHasta = ahora + 80;
    } else {
      autenticado = false;
      if (++fallos >= MAX_FALLOS) {
        fallos = 0; bloqueadoHasta = ahora + BLOQUEO_MS;
        enviar("AUTH_LOCK");
      } else {
        enviar("AUTH_FAIL");
      }
    }
    return;
  }

  if (strcmp(payload, "PING") == 0) { enviar(autenticado ? "PONG" : "AUTH_REQ"); return; }

  // A partir de aquí todo comando exige sesión autenticada
  if (!autenticado) { enviar("AUTH_REQ"); return; }

  // Cambiar nota individual: N<1-6>:<freqHz>
  if (payload[0] == 'N' && payload[2] == ':' && payload[1] >= '1' && payload[1] <= '6') {
    int idx = payload[1] - '1';
    char *fin;
    long freq = strtol(payload + 3, &fin, 10);
    if (*fin != '\0' || freq < 65 || freq > 2000) { enviar("ERR:RANGO"); return; }
    notas[idx] = (int)freq;
    char resp[16];
    snprintf(resp, sizeof(resp), "OK:N%d:%d", idx + 1, notas[idx]);
    enviar(resp);
    vistaPreviaEfecto = false; tone(pinBuzzer, notas[idx], 100); beepHasta = ahora + 100;
    return;
  }

  // Sincronizar las 6 notas de una sola vez: S:f1,f2,f3,f4,f5,f6
  if (strncmp(payload, "S:", 2) == 0) {
    int nuevas[NUM_CUERDAS];
    char *p = payload + 2;
    for (int i = 0; i < NUM_CUERDAS; i++) {
      char *fin;
      long f = strtol(p, &fin, 10);
      if (f < 65 || f > 2000) { enviar("ERR:RANGO"); return; }
      if (i < NUM_CUERDAS - 1 && *fin != ',') { enviar("ERR:FORMATO"); return; }
      if (i == NUM_CUERDAS - 1 && *fin != '\0') { enviar("ERR:FORMATO"); return; }
      nuevas[i] = (int)f;
      p = fin + 1;
    }
    for (int i = 0; i < NUM_CUERDAS; i++) notas[i] = nuevas[i];
    enviar("OK:S");
    vistaPreviaEfecto = false; tone(pinBuzzer, notas[0], 100); beepHasta = ahora + 100;
    return;
  }

  // Efecto de sonido: E<1-7>
  if (payload[0] == 'E' && strlen(payload) == 2) {
    int ef = payload[1] - '0';
    if (ef < 1 || ef > 7) { enviar("ERR:RANGO"); return; }
    efectoActual = (uint8_t)ef;
    char resp[8] = "OK:E0";
    resp[4] = payload[1];
    enviar(resp);
    ultimoIndiceNota = -1;
    reproducirEfecto(0, ahora); vistaPreviaEfecto = true; beepHasta = ahora + 300;
    return;
  }

  // Recalibración remota de sensores LDR
  if (strcmp(payload, "CAL") == 0) {
    calibrarSensores();
    ultimaTrama = millis();
    enviar("OK:CAL");
    return;
  }

  // Prueba de la primera cuerda con el efecto activo
  if (strcmp(payload, "TEST") == 0) {
    ultimoIndiceNota = -1;
    reproducirEfecto(0, ahora); vistaPreviaEfecto = true; beepHasta = ahora + 300;
    enviar("OK:TEST");
    return;
  }

  enviar("ERR:CMD");
}

void leerBluetooth() {
  while (bluetooth.available()) {
    char c = (char)bluetooth.read();
    if (c == '\r') continue;
    if (c == '\n') {
      if (!desborde && largo > 0) {
        buffer[largo] = '\0';
        char *payload = validarTrama(buffer);
        if (payload) procesar(payload);
        else enviar("ERR:CHECKSUM");
      }
      largo = 0; desborde = false;
    } else if (largo < MAX_TRAMA) {
      buffer[largo++] = c;
    } else {
      desborde = true;
    }
  }
}

// ================================================================= Setup y Loop
void setup() {
  Serial.begin(9600);
  bluetooth.begin(9600);
  pinMode(pinBuzzer, OUTPUT);

  // Configurar y encender de inmediato los 6 módulos láser
  for (int i = 0; i < NUM_CUERDAS; i++) {
    pinMode(pinLaser[i], OUTPUT);
    digitalWrite(pinLaser[i], HIGH);
  }

  calibrarSensores();
  Serial.println("Arpa Laser Conectada");
}

void loop() {
  bool algunaNotaSonando = false;
  unsigned long ahora = millis();

  // 1. Recorrer los 6 sensores LDR
  for (int i = 0; i < NUM_CUERDAS; i++) {
    int valorLectura = analogRead(pinLDR[i]);

    if (valorLectura < umbralLuz[i]) {
      algunaNotaSonando = true;
      if (!notaActiva[i]) ultimoIndiceNota = -1;
      vistaPreviaEfecto = false;
      reproducirEfecto(i, ahora);

      if (!notaActiva[i]) {
        notaActiva[i] = true;

        Serial.print("Cuerda "); Serial.print(i + 1);
        Serial.print(" (Valor: "); Serial.print(valorLectura);
        Serial.print(" | Nota: "); Serial.print(notas[i]);
        Serial.println(" Hz) -> Tocando nota!");

        if (autenticado) {
          char msg[16] = "NOTA_ACTIVADA:0";
          msg[14] = '1' + i;
          enviar(msg);
        }
      }
    } else {
      notaActiva[i] = false;
    }
  }

  if (!algunaNotaSonando) {
    if (ahora >= beepHasta) ultimoIndiceNota = -1;
    if (vistaPreviaEfecto && ahora < beepHasta && ultimoIndiceNota >= 0) reproducirEfecto(ultimoIndiceNota, ahora);
    else if (ahora >= beepHasta) { vistaPreviaEfecto = false; noTone(pinBuzzer); }
  }

  // 2. Comprobación de tramas Bluetooth seguras
  leerBluetooth();

  // 3. Expiración de sesión tras 15 s sin actividad
  if (autenticado && ahora - ultimaTrama > SESION_MS) {
    autenticado = false;
    enviar("AUTH_REQ");
  }

  delay(20);
}
