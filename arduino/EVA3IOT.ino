#include <SoftwareSerial.h>
#include "HX711.h"
#include "DHT.h"

// Bluetooth HC-05: Pin 10 -> TXD, Pin 11 -> RXD
SoftwareSerial BT(10, 11);

// Balanza HX711: Pin 4 -> DT, Pin 5 -> SCK
const int LOADCELL_DOUT_PIN = 4;
const int LOADCELL_SCK_PIN = 5;
HX711 scale;

// Sensor de Temperatura DHT11: Pin Digital 2
#define DHTPIN 2
#define DHTTYPE DHT11
DHT dht(DHTPIN, DHTTYPE);

// Relé (Active LOW: LOW = Encendido, HIGH = Apagado)
const int RELAY_PIN = 7;

// Seguridad ISO 27000
const String SECURITY_TOKEN = "AUTH:TI3042|";
const float FACTOR_CALIBRACION = 550.0;
const float TEMP_MINIMA_SEGURIDAD = 18.0; // Umbral demo laboratorio (producción: 60.0 °C)

float pesoActual = 0.0;
float pesoSuavizado = 0.0;
float tempActual = 22.0;
bool dispensando = false;
int objetivoGramos = 200;

unsigned long ultimoEnvio = 0;
unsigned long ultimaLecturaTemp = 0;
unsigned long inicioDispensado = 0;

void setup() {
  Serial.begin(9600);
  BT.begin(9600);

  pinMode(RELAY_PIN, OUTPUT);
  digitalWrite(RELAY_PIN, HIGH);

  dht.begin();

  scale.begin(LOADCELL_DOUT_PIN, LOADCELL_SCK_PIN);
  scale.set_scale(FACTOR_CALIBRACION);
  delay(200);
  scale.tare();
}

void loop() {
  // 1. Lectura de peso real con filtro de suavizado
  if (scale.is_ready()) {
    float lecturaCruda = abs(scale.get_units(1));
    if (lecturaCruda < 1.5) lecturaCruda = 0.0;

    pesoSuavizado = (pesoSuavizado * 0.65) + (lecturaCruda * 0.35);
    pesoActual = (pesoSuavizado < 1.5) ? 0.0 : pesoSuavizado;
  }

  // 2. Lectura real del DHT11 cada 1 segundo (evita bloquear el Bluetooth)
  if (millis() - ultimaLecturaTemp >= 1000) {
    ultimaLecturaTemp = millis();
    float t = dht.readTemperature();
    if (!isnan(t)) {
      tempActual = t;
    }
  }

  // 3. Corte automático del relé al alcanzar los gramos elegidos
  if (dispensando) {
    if (pesoActual >= objetivoGramos || (millis() - inicioDispensado > 20000)) {
      detenerBomba();
    }
  }

  // 4. Envío de telemetría real a Android y Firebase cada 500 ms con Checksum
  if (millis() - ultimoEnvio >= 500) {
    ultimoEnvio = millis();
    int checksum = ((int)pesoActual + (int)tempActual) % 97;

    String trama = "BT_DATA:WEIGHT:" + String(pesoActual, 1) +
                   ";TEMP:" + String(tempActual, 1) +
                   ";STATE:" + (dispensando ? "ON" : "OFF") +
                   ";CHK:" + String(checksum) + "\n";
    BT.print(trama);
  }

  // 5. Recepción de comandos autenticados desde la App Android
  if (BT.available() > 0) {
    String raw = BT.readStringUntil('\n');
    raw.trim();

    if (!raw.startsWith(SECURITY_TOKEN)) {
      return;
    }

    String cmd = raw.substring(SECURITY_TOKEN.length());

    if (cmd.startsWith("SET:")) {
      int dosis = cmd.substring(4).toInt();
      if (dosis > 0 && tempActual >= TEMP_MINIMA_SEGURIDAD) {
        objetivoGramos = dosis;
        iniciarBomba();
      }
    } 
    else if (cmd == "TARE") {
      scale.tare();
      pesoActual = 0.0;
      pesoSuavizado = 0.0;
    } 
    else if (cmd == "STOP") {
      detenerBomba();
    }
  }
}

void iniciarBomba() {
  dispensando = true;
  inicioDispensado = millis();
  digitalWrite(RELAY_PIN, LOW);
}

void detenerBomba() {
  dispensando = false;
  digitalWrite(RELAY_PIN, HIGH);
}