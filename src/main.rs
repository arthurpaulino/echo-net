use esp_idf_svc::{
    eventloop::EspEventLoop,
    hal::{
        adc::oneshot::{config::AdcChannelConfig, AdcChannelDriver, AdcDriver},
        delay::FreeRtos,
        gpio::{Gpio2, Output, PinDriver},
        modem::WifiModem,
        prelude::Peripherals,
    },
    sys::{err_enum_t_ERR_OK, nvs_flash_init},
    systime::EspSystemTime,
    wifi::{ClientConfiguration, Configuration, EspWifi},
};
use heapless::String as Str;
use std::{
    collections::BinaryHeap,
    net::{Ipv4Addr, SocketAddrV4, UdpSocket},
    sync::{mpsc, Mutex},
    thread,
    time::Duration,
};

// Wifi constants defined at build time
const SSID: &str = env!("ECHONET_WIFI_SSID");
const PASSWORD: &str = env!("ECHONET_WIFI_PASSWORD");

// Producer constants
const NUM_CONTIGUOUS_READS: usize = 20000;
const DELAY_BETWEEN_CONTIGUOUS_READS: u32 = 10;

// Consumer constants
const HEAP_PERCENTILE: usize = 5;
const HEAP_CAPACITY: usize = 18000;
const MAX_RAW: usize = 950;
const SEND_INTERVAL: Duration = Duration::from_millis(1000);
const MULTICAST_ADDR: ([u8; 4], u16) = ([224, 0, 0, 1], 5000);

type Res<T> = Result<T, Box<dyn std::error::Error>>;

/// Holds the wifi connection so it isn't dropped
static WIFI_CONN: Mutex<Option<EspWifi>> = Mutex::new(None);

struct Led<'a> {
    pin_driver: PinDriver<'a, Gpio2, Output>,
    is_on: bool,
}

impl Led<'_> {
    #[inline]
    fn new(gpio2: Gpio2) -> Res<Self> {
        let pin_driver = PinDriver::output(gpio2)?;
        let is_on = false;
        Ok(Led { pin_driver, is_on })
    }

    #[inline]
    fn set(&mut self, on: bool) -> Res<()> {
        if on {
            self.pin_driver.set_high()?;
        } else {
            self.pin_driver.set_low()?;
        }
        self.is_on = on;
        Ok(())
    }

    #[inline]
    fn toggle(&mut self) -> Res<()> {
        self.set(!self.is_on)
    }
}

#[inline]
fn connect_to_wifi(led: &mut Led) -> Res<()> {
    let modem = unsafe { WifiModem::new() };
    let esp_event_loop = EspEventLoop::take()?;
    let mut wifi = EspWifi::new(modem, esp_event_loop, None)?;

    let mut ssid = Str::new();
    let mut password = Str::new();
    assert!(ssid.push_str(SSID).is_ok());
    assert!(password.push_str(PASSWORD).is_ok());

    wifi.set_configuration(&Configuration::Client(ClientConfiguration {
        ssid,
        password,
        ..Default::default()
    }))?;

    assert_eq!(unsafe { nvs_flash_init() }, err_enum_t_ERR_OK);

    wifi.start()?;
    wifi.connect()?;

    let netif = wifi.sta_netif();
    while !netif.is_up()? {
        led.toggle()?;
        log::info!("Waiting for Wifi connection");
        FreeRtos::delay_ms(1000);
    }
    log::info!("Connected to Wifi");
    led.set(false)?;
    *WIFI_CONN.lock()? = Some(wifi);
    Ok(())
}

fn main() -> Res<()> {
    // It is necessary to call this function once. Otherwise some patches to the runtime
    // implemented by esp-idf-sys might not link properly. See https://github.com/esp-rs/esp-idf-template/issues/71
    esp_idf_svc::sys::link_patches();

    // Bind the log crate to the ESP Logging facilities
    esp_idf_svc::log::EspLogger::initialize_default();

    let peripherals = Peripherals::take()?;
    let mut led = Led::new(peripherals.pins.gpio2)?;

    // Thread-locking, until the Wifi connection becomes stable
    connect_to_wifi(&mut led)?;

    // The producer will send data to the consumer through a channel
    let (sx, rx) = mpsc::channel();

    // The consumer loop is spawned on a separate thread
    thread::spawn(move || {
        let socket = UdpSocket::bind(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, 0))
            .expect("Failed to bind socket");
        let ([a, b, c, d], p) = MULTICAST_ADDR;
        let addr = SocketAddrV4::new(Ipv4Addr::new(a, b, c, d), p);
        let timer = EspSystemTime;

        let mut heap = BinaryHeap::with_capacity(HEAP_CAPACITY);
        let mut start = timer.now();
        for raw in rx {
            let adj = ((MAX_RAW - raw as usize) * 255 / MAX_RAW) as u8;
            heap.push(adj);
            let now = timer.now();
            if now - start >= SEND_INTERVAL {
                let heap_len = heap.len();
                log::info!("Heap length: {heap_len}");
                if heap_len > HEAP_CAPACITY {
                    log::warn!(
                        "Heap length > {HEAP_CAPACITY}. Consider increasing its initial capacity"
                    );
                }

                // Compute the average of the highest `HEAP_PERCENTILE` noise values
                let avg = {
                    let num_sum = HEAP_PERCENTILE * heap_len / 100;
                    let mut sum = 0;
                    for _ in 0..num_sum {
                        let Some(adj) = heap.pop() else {
                            break;
                        };
                        sum += adj as usize; // Sum as `usize` so we don't lose precision
                    }
                    (sum / num_sum) as u8 // Truncate down to `u8` again
                };

                socket.send_to(&[avg], addr).expect("Failed to send data");
                log::info!("Noise: {avg}");

                start = now;
                heap.clear();
            }
        }
    });

    // The producer loop lives in the main thread
    let adc1 = AdcDriver::new(peripherals.adc1)?;
    let config = AdcChannelConfig::new();
    let mut adc_channel = AdcChannelDriver::new(&adc1, peripherals.pins.gpio35, &config)?;
    let mut num_reads = 0;
    loop {
        sx.send(adc1.read(&mut adc_channel)?)?;
        num_reads += 1;
        if num_reads >= NUM_CONTIGUOUS_READS {
            FreeRtos::delay_ms(DELAY_BETWEEN_CONTIGUOUS_READS);
            num_reads = 0;
        }
    }
}
