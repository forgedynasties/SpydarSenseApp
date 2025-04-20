# Spydar Sense

## Overview

Spydar Sense is an Android application designed to detect hidden spy cameras using WiFi signal analysis. The app captures and analyzes Channel State Information (CSI) and network bitrate data via the Nexmon CSI toolkit to identify potential spy cameras in your vicinity.

This project is built for Android devices with compatible WiFi chipsets, particularly those supporting monitor mode and CSI extraction (such as devices with the Broadcom BCM4339 chipset).

## Features

- **Initial Setup Assistant**: Step-by-step configuration to ensure your device is properly prepared
- **Network Scanning**: Identify and analyze surrounding WiFi networks
- **CSI Data Collection**: Collect real-time Channel State Information from WiFi signals
- **Bitrate Analysis**: Monitor network traffic patterns to identify suspicious activity
- **Dark/Light Theme**: Comfortable viewing in any environment

## Requirements

- Nexus 5

## Setup Instructions

1. **Root Access**: Ensure your device has root access
2. **Initial Setup**: Follow the setup screen instructions to:
   - Verify root permissions
   - Enable WiFi interface in monitor mode
   - Configure necessary network parameters

## Usage

### Home Screen
- View a list of available networks in your vicinity
- Select a network to begin spy camera detection

### Detection Screen
- Start/stop data collection for the selected network
- View real-time analysis of CSI and bitrate data
- Clear collected data when needed

## Development Status

This application is currently under active development. Key features being worked on:

- Optimizing data processing algorithms
- Improving detection accuracy
- Adding visualization tools for CSI data
- Implementing machine learning for automatic detection

## Contributing

Contributions are welcome! If you'd like to contribute, please:

1. Fork the repository
2. Create a feature branch
3. Submit a pull request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Acknowledgments

- Nexmon team for the CSI toolkit
- The Android open-source community
