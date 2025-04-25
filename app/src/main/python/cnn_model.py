import pandas as pd
import numpy as np
import tensorflow as tf
import json
from sklearn.model_selection import train_test_split
import matplotlib.pyplot as plt
from sklearn.metrics import (precision_score, recall_score, f1_score, confusion_matrix,
                             roc_curve, auc, classification_report)

# Load the dataset CSV
csv_file = "dataset.csv"  # update with your CSV file path if needed
data = pd.read_csv(csv_file)

# Map Activity to binary labels:
# "static" -> 0, "Mild" or "Aggressive" -> 1
def map_activity(activity):
    act = activity.lower().strip()
    if act == "static":
        return 0
    elif act in ["mild", "aggressive"]:
        return 1
    else:
        # For unexpected values, default to 1 (or handle accordingly)
        return 1

data["label"] = data["Activity"].apply(map_activity)

# Parse the Bitrate_Sequence column (stored as a JSON string) to a list
data["Bitrate_Sequence"] = data["Bitrate_Sequence"].apply(json.loads)

# Convert the sequence column to a numpy array
# This will result in an array of shape (num_samples, sequence_length)
X = np.array(data["Bitrate_Sequence"].tolist()) / 1e4  # scaling the values
y = data["label"].values

print("Feature shape:", X.shape)
print("Labels shape:", y.shape)
print("Class distribution:", np.bincount(y))

# If the array is 2D, add a channel dimension to make it 3D (required for Conv1D)
if len(X.shape) == 2:
    X = np.expand_dims(X, axis=-1)

# Split data into training, validation, and test sets with stratification
# First, reserve 20% of the data as the test set
X_train_val, X_test, y_train_val, y_test = train_test_split(
    X, y, test_size=0.2, stratify=y, random_state=42
)
# Further split the train+validation set into training (80%) and validation (20%) sets
X_train, X_val, y_train, y_val = train_test_split(
    X_train_val, y_train_val, test_size=0.2, stratify=y_train_val, random_state=42
)

print("Train class distribution:", np.bincount(y_train))
print("Validation class distribution:", np.bincount(y_val))
print("Test class distribution:", np.bincount(y_test))

# Define a lightweight 1D CNN model
sequence_length = X.shape[1]  # should be 30 for a 3s sequence at 0.1s sampling
num_channels = X.shape[2]

model = tf.keras.Sequential([
    tf.keras.layers.Input(shape=(sequence_length, num_channels)),
    tf.keras.layers.Conv1D(filters=16, kernel_size=3, activation='relu'),
    tf.keras.layers.MaxPooling1D(pool_size=2),
    tf.keras.layers.Conv1D(filters=32, kernel_size=3, activation='relu'),
    tf.keras.layers.MaxPooling1D(pool_size=2),
    tf.keras.layers.Flatten(),
    tf.keras.layers.Dense(64, activation='relu'),
    tf.keras.layers.Dense(1, activation='sigmoid')  # Binary classification
])

# Compile the model
model.compile(optimizer='adam', loss='binary_crossentropy', metrics=['accuracy'])
model.summary()

# Set up EarlyStopping callback: monitor validation accuracy with a patience of 5 epochs.
early_stopping = tf.keras.callbacks.EarlyStopping(
    monitor='val_accuracy',
    patience=5,
    restore_best_weights=True
)

# Train the model until accuracies converge (with a high maximum epochs count)
history = model.fit(
    X_train, y_train,
    epochs=100,  # high max epochs; training will stop early if converged
    batch_size=32,
    validation_data=(X_val, y_val),
    callbacks=[early_stopping]
)

# Evaluate the model on the test set (this returns loss and accuracy)
test_loss, test_accuracy = model.evaluate(X_test, y_test)
print("Test accuracy:", test_accuracy)

# Save the model weights for deployment
# model.save_weights("model_weights_br.h5")
# print("Model weights saved to 'model_weights_br.h5'")

# Generate predictions on the test set
y_pred_prob = model.predict(X_test)
# Convert probabilities to binary predictions using a threshold of 0.5
y_pred = (y_pred_prob >= 0.5).astype(int)

# Compute additional metrics
precision = precision_score(y_test, y_pred)
recall = recall_score(y_test, y_pred)
f1 = f1_score(y_test, y_pred)
cm = confusion_matrix(y_test, y_pred)
report = classification_report(y_test, y_pred)
fpr, tpr, thresholds = roc_curve(y_test, y_pred_prob)
roc_auc = auc(fpr, tpr)

print("\nPrecision: {:.4f}".format(precision))
print("Recall: {:.4f}".format(recall))
print("F1 Score: {:.4f}".format(f1))
print("\nConfusion Matrix:")
print(cm)
print("\nClassification Report:")
print(report)
print("ROC AUC: {:.4f}".format(roc_auc))

# Plot ROC curve
plt.figure(figsize=(6, 5))
plt.plot(fpr, tpr, label='ROC curve (area = {:.4f})'.format(roc_auc))
plt.plot([0, 1], [0, 1], 'k--', label='Chance')
plt.xlabel('False Positive Rate')
plt.ylabel('True Positive Rate')
plt.title('Receiver Operating Characteristic (ROC) Curve')
plt.legend(loc="lower right")
plt.show()

# Plot training & validation loss and accuracy
plt.figure(figsize=(12, 5))

# Loss subplot
plt.subplot(1, 2, 1)
plt.plot(history.history['loss'], label='Train Loss')
plt.plot(history.history['val_loss'], label='Validation Loss')
plt.title("Training and Validation Loss")
plt.xlabel("Epoch")
plt.ylabel("Loss")
plt.legend()

# Accuracy subplot
plt.subplot(1, 2, 2)
plt.plot(history.history['accuracy'], label='Train Accuracy')
plt.plot(history.history['val_accuracy'], label='Validation Accuracy')
plt.title("Training and Validation Accuracy")
plt.xlabel("Epoch")
plt.ylabel("Accuracy")
plt.legend()

plt.tight_layout()
plt.show()
