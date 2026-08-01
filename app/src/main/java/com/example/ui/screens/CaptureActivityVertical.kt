package com.example.ui.screens

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * El escáner de código de barras del Catálogo, en vertical.
 *
 * ZXing declara su `CaptureActivity` con `screenOrientation="sensorLandscape"`,
 * así que escanear un producto obligaba a dar vuelta el teléfono — justo al
 * revés que el Modo Súper, que usa CameraX y respeta la orientación de la app.
 *
 * Esta subclase no agrega comportamiento: existe solamente para poder declararla
 * en NUESTRO manifest con `screenOrientation="portrait"`, que es la única forma
 * de sobrescribir la orientación que impone la librería.
 */
class CaptureActivityVertical : CaptureActivity()
