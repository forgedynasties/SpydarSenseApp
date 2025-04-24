# Model Weights for SpyCamClassifier

This folder contains the trained model weights for the spy camera detection system.

## Files:
- `model_weights.h5`: Weights for the CSI model
- `model_weights_br.h5`: Weights for the Bitrate model

## Converting to TFLite

To convert these weights to TFLite format for use in the Android app:

1. Make sure you have TensorFlow installed: `pip install tensorflow`
2. Run the conversion script:
   ```
   python app/src/main/python/convert_to_tflite.py
   ```
3. The script will:
   - Load the model architecture and weights
   - Convert to TFLite format
   - Save the converted models to `app/src/main/assets/`
   - Verify the conversion with test predictions

## Model Architecture

Both models use the same CNN architecture:
- 1D Convolutional layer (16 filters, kernel size 3)
- Max Pooling layer (pool size 2)
- 1D Convolutional layer (32 filters, kernel size 3)
- Max Pooling layer (pool size 2)
- Flatten layer
- Dense layer (64 units)
- Output layer (1 unit with sigmoid activation)

Input shape: `[1, 30, 1]` (batch size, sequence length, channels)
