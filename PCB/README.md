# Project KigEyes PCBs

## kigeyes-mainboard

| versions | status |
| :-- | :-- |
| prototype | assembled, waiting for HDMI to DP converter, do not build |
| v1 | pending |

- Type C (custom protocol) power input (up to 15V) from `kigeyes-power-io-board`, includes single USB 2.0, dual USB 3.2 Gen 1, and I2C
- Raspberry Pi Compute Module 5
- HDMI to DisplayPort to Type C DP Alt mode converter
- MIPI camera multiplexer to connect up to 4 cameras (2 active at once)

## kigeyes-power-io-board

| ![kigeyes-power-io-board-v1-front](https://blog.jerrymk.com/media/blog/kigeyes-dev-blog-20261005/pxl-20261007-131600379-mp-3.webp) | ![kigeyes-power-io-board-v1-back](https://blog.jerrymk.com/media/blog/kigeyes-dev-blog-20261005/pxl-20261007-131838755-mp-2.webp) |
| --- | --- |


| versions | status |
| :-- | :-- |
| [v1](https://github.com/jrymk/KigEyes/tree/7198f1973b7d364ebd13c92b04e8cc514ec3cece/PCB/kigeyes-power-io-board) | finalized, tested |

- USB PD input (up to 15V 3A)
- 1S LiFePO4 BQ25306 charger (up to 3A) and voltage booster
- Type C (custom protocol) power output (5~15V) to `kigeyes-mainboard`
- USB 2.0 hub
- USB Type C DFP (5V 1.5A, USB 3.2 Gen 1, PD-compliant)
- USB Type A DFP (5V 0.9A guaranteed, USB 3.2 Gen 1)
- USB Port AUX (JST PH, USB 2.0)
- USB Port ENV (JST PH, USB 2.0)
- 24V/12V fan output with speed control

## IMX675-camera-module

- IMX675 sensor with
    - reasonable sensor size (1/2.8") for conical M12 lenses for a small opening
    - reasonably high resolution (2592×1944 5.12MP) for clear text reading but not too high to be wasteful by the mediocre resolution of conical lenses, Raspberry Pi processing power, and 1080p AR glasses
    - STARVIS 2 for okay low light performance
- MCU controlled power sequencing, power rail monitoring, and sensor configuration
- LED status indication
- Stereo camera sync signals through the 22P connector

Note: Use opposite-side (B-type) 22P 0.5mm FFC cables

| versions | status |
| :-- | :-- |
| v1 | tested |

## eye-tracker-mainboard

| versions | status |
| :-- | :-- |
| [a1](https://github.com/jrymk/KigEyes/tree/e749d2dd36b2bbe4de054390257230e3775aa7c6/PCB/archive/eye-tracker-mainboard) | scrapped |
| v1 | postponed |

- Dual ESP32P4 processors
- Connectors to `OVM6211-fpc`
- USB 2.0 hub