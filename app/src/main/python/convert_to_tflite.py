import tensorflow as tf
import numpy as np
import os

def convert_h5_to_tflite(h5_path, tflite_path, input_shape):
    """
    Convert a TensorFlow model from h5 format to TFLite format
    
    Args:
        h5_path: Path to h5 model file
        tflite_path: Path to save TFLite model
        input_shape: Input shape for the model (e.g., [1, 30, 1] for time series with length 30)
    """
    print(f"Converting {h5_path} to TFLite format...")
    
    # Recreate the exact same model, including its weights and optimizer
    if os.path.exists(h5_path):
        # Create the model structure - should match the original model architecture
        model = tf.keras.Sequential([
            tf.keras.layers.Input(shape=input_shape[1:]),
            tf.keras.layers.Conv1D(filters=16, kernel_size=3, activation='relu'),
            tf.keras.layers.MaxPooling1D(pool_size=2),
            tf.keras.layers.Conv1D(filters=32, kernel_size=3, activation='relu'),
            tf.keras.layers.MaxPooling1D(pool_size=2),
            tf.keras.layers.Flatten(),
            tf.keras.layers.Dense(64, activation='relu'),
            tf.keras.layers.Dense(1, activation='sigmoid')
        ])
        
        # Load the weights
        model.load_weights(h5_path)
        print(f"Model loaded from {h5_path}")
        
        # Convert the model to TFLite format
        converter = tf.lite.TFLiteConverter.from_keras_model(model)
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        tflite_model = converter.convert()
        
        # Save the TFLite model
        with open(tflite_path, 'wb') as f:
            f.write(tflite_model)
        
        print(f"Model converted and saved to {tflite_path}")
        
        # Generate a test input for verification
        test_input = np.random.random(input_shape).astype(np.float32)
        
        # Get prediction from the original model
        original_prediction = model.predict(test_input)
        
        # Load the TFLite model
        interpreter = tf.lite.Interpreter(model_path=tflite_path)
        interpreter.allocate_tensors()
        
        # Get input and output tensors.
        input_details = interpreter.get_input_details()
        output_details = interpreter.get_output_details()
        
        # Test the TFLite model on random input data.
        interpreter.set_tensor(input_details[0]['index'], test_input)
        interpreter.invoke()
        tflite_prediction = interpreter.get_tensor(output_details[0]['index'])
        
        # Compare results
        print(f"Original model prediction: {original_prediction[0][0]}")
        print(f"TFLite model prediction: {tflite_prediction[0][0]}")
        
        return True
    else:
        print(f"Error: Model file {h5_path} not found")
        return False

if __name__ == "__main__":
    # Define weights folder - update this to match your project structure
    weights_folder = "d:/SpydarSense/App/weights"
    
    # Define paths for CSI model (using model_weights.h5 as CSI model)
    csi_h5_path = os.path.join(weights_folder, "model_weights.h5")
    
    # Define paths for bitrate model
    br_h5_path = os.path.join(weights_folder, "model_weights_br.h5")
    
    # Define output paths in the assets folder
    assets_folder = "d:/SpydarSense/App/app/src/main/assets"
    csi_tflite_path = os.path.join(assets_folder, "csi_model.tflite")
    br_tflite_path = os.path.join(assets_folder, "br_model.tflite")
    
    # Input shape parameters
    csi_input_shape = [1, 30, 1]  # Batch size, sequence length, channels
    br_input_shape = [1, 30, 1]   # Batch size, sequence length, channels
    
    # Create assets directory if it doesn't exist
    os.makedirs(assets_folder, exist_ok=True)
    
    # Convert CSI model
    print(f"Converting CSI model from {csi_h5_path}")
    success_csi = convert_h5_to_tflite(csi_h5_path, csi_tflite_path, csi_input_shape)
    
    # Convert bitrate model
    print(f"Converting Bitrate model from {br_h5_path}")
    success_br = convert_h5_to_tflite(br_h5_path, br_tflite_path, br_input_shape)
    
    if success_csi and success_br:
        print("Both models successfully converted to TFLite format")
        print(f"TFLite models saved to: {assets_folder}")
    else:
        print("Error in conversion process")
        if not success_csi:
            print(f"Failed to convert CSI model from {csi_h5_path}")
        if not success_br:
            print(f"Failed to convert Bitrate model from {br_h5_path}")
