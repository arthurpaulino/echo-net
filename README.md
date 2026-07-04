# echo-net

Source code for embedding echo-net on an ESP32.

# Build

After [setting up a development environment](https://docs.espressif.com/projects/rust/book/getting-started/index.html),
the project can be built with `cargo build`, but it requires the following env variables to be set:

* `ECHONET_WIFI_SSID`
* `ECHONET_WIFI_PASSWORD`

# Flashing

Flash your ESP32 with [`espflash`](https://docs.espressif.com/projects/rust/book/getting-started/tooling/espflash.html).

We recommend `cargo-espflash`, which enables

```bash
ECHONET_WIFI_SSID=MyWifi ECHONET_WIFI_PASSWORD=MyPassword cargo espflash flash --release --monitor
```
