# Spydar Sense

## Overview

Spydar Sense is an Android application designed to detect spy cameras using Channel State Information (CSI) and bitrate data collected via the Nexmon CSI tool on a Nexus 5 smartphone. The application leverages machine learning techniques to analyze the collected data and identify potential spy cameras in the vicinity.

This project is currently in the development phase and is compatible with the Nexus 5 equipped with the Broadcom BCM4339 chipset.

## Features

- **CSI Data Collection**: Utilizes Nexmon CSI to gather Channel State Information from Wi-Fi signals.
- **Bitrate Monitoring**: Collects bitrate data to complement CSI data for more accurate detection.
- **Machine Learning**: Employs machine learning algorithms to analyze the collected data and detect anomalies indicative of spy cameras.
- **User Interface**: Provides a user-friendly interface for setup, data collection, and detection.

## Open in Android Studio

1. Open the project in Android Studio.
2. Sync the project with Gradle files.

## Run the Application

1. Connect your Nexus 5 device via USB.
2. Run the application from Android Studio.

## Usage

### Setup

- Open the app and follow the setup instructions on the `SetupScreen`.

### Detection

- Navigate to the `DetectSpyCamScreen` to start the detection process.
- The app will collect CSI and bitrate data and analyze it for potential spy cameras.

## Development

This project is currently in the development phase. Contributions are welcome! Please feel free to fork the repository, make changes, and submit pull requests.

### To-Do List

- Implement data preprocessing pipeline.
- Develop machine learning models for spy camera detection.
- Optimize data collection and analysis for real-time detection.
- Enhance the user interface for better user experience.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- Nexmon team for the CSI tool.
- Broadcom for the BCM4339 chipset.
- The open-source community for various libraries and tools used in this project.

## Contact

For any questions or suggestions, please contact [ccdd4lii@gmail.com] or open an issue on the GitHub repository.
