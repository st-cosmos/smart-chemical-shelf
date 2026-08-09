# firmware — 스마트 선반 노드 (E73-2G4M08S1C / Thread)

E73-2G4M08S1C (nRF52840) 커스텀 보드용 선반 노드 펌웨어.
Thread 메시로 게이트웨이([`../gateway`](../gateway))에 붙어서 로드셀 무게를 보고하고, LED 점등 명령을 받아 처리합니다.

> 이전의 ESP8266(PlatformIO + Wi-Fi/HTTP) 버전은 git 히스토리에 있습니다.
> 이 버전은 **nRF Connect SDK v3.4.0 (Zephyr 4.4) + OpenThread + CoAP** 스택으로 완전히 교체된 것이며,
> 실제 하드웨어(커스텀 보드 2대)에서 빌드·플래싱·계측까지 검증된 코드입니다.

## 동작

* **무게** — `SHELF_MEASURE_INTERVAL_MS`(기본 10초)마다 로드셀 전원을 켜고, CS1237 로 8샘플을 평균한 뒤 전원을 끕니다. 직전 보고값 대비 `SHELF_ADC_DELTA_THRESHOLD` 이상 변했을 때만 게이트웨이로 CoAP POST 를 보냅니다 (변화가 없어도 5분에 한 번 하트비트).
* **LED** — 700 ms 주기로 게이트웨이에 CoAP GET 을 보내 명령 상태를 읽고 LED 를 켜거나 끕니다. 게이트웨이가 계속 응답하지 않으면 LED 를 끕니다.
* **배터리** — 보고 시마다 SAADC 의 내부 VDDH/5 탭으로 배터리 전압(mV)을 함께 보냅니다. 4.4 V 초과면 USB 전원 상태입니다.
* **게이트웨이 탐색** — 주소 설정이 필요 없습니다. `ff03::1` 멀티캐스트로 시작해 응답한 게이트웨이의 유니캐스트 주소를 기억합니다.

## 구성

```
CMakeLists.txt / Kconfig / prj.conf     애플리케이션 빌드 + 설정
sysbuild.conf, sysbuild/               MCUboot (USB CDC 시리얼 리커버리)
boards/nrf52840dk_nrf52840.overlay     E73 모듈 핀 배치 + USB 콘솔
src/cs1237.[ch]                        CS1237 비트뱅 드라이버 (전원 게이팅 포함)
src/shelf_cal.[ch]                     영점/캘리브레이션 (NVS 저장 + `shelf` 셸 명령)
src/shelf_coap.[ch]                    CoAP 클라이언트 (리포트 + LED 폴링)
src/main.c                             측정 스레드 / LED 스레드 / 배터리 측정
docs/build-and-flash.md                빌드, 플래싱, 부트로더, 캘리브레이션 — 삽질 기록 전체
```

## 빠른 시작

```powershell
cd C:\Users\seadmisk\workspace\smart-chemical-shelf\firmware
west build -b nrf52840dk/nrf52840 --sysbuild -p always .

$env:NRFUTIL_HOME = "C:\ncs\toolchains\dcbdc366a1\nrfutil\home"
Set-Alias nrfutil "C:\ncs\toolchains\dcbdc366a1\nrfutil\bin\nrfutil.exe"
$opt = "chip_erase_mode=ERASE_RANGES_TOUCHED_BY_FIRMWARE,verify=VERIFY_READ"
nrfutil device program --firmware build\mcuboot\zephyr\zephyr.hex --options $opt
nrfutil device program --firmware build\firmware\zephyr\zephyr.signed.hex --options "$opt,reset=RESET_SYSTEM"
```

최초 1회는 SWD 가 필요하고 (nRF52840 에는 공장 USB 부트로더가 없음), 그 전에 `UICR.REGOUT0` 를 3.3V 로 설정해야 합니다. **[docs/build-and-flash.md §4](docs/build-and-flash.md)** 의 순서를 그대로 따르세요 — 함정이 여러 개 있습니다.

* **`west flash` 는 권장하지 않습니다.** `--erase` 를 붙이지 않았는데도 `UICR.REGOUT0` 가 지워지는 것을 확인했습니다. 지워지면 VDD 가 1.8V 로 돌아가고 CS1237 이 동작하지 않습니다.
* `nrfutil device program` 의 기본 `chip_erase_mode` 는 `ERASE_ALL` 이라 UICR 을 지웁니다. 위처럼 `ERASE_RANGES_TOUCHED_BY_FIRMWARE` 를 반드시 지정하세요.
* 콘솔/셸은 USB-C 로 열립니다. VID 는 **`0x2FE3`**(Zephyr) 입니다.

**이후 업데이트는 SWD 없이 USB-C 만으로 됩니다** (검증 완료). 리셋 후 5초 안에:

```powershell
mcumgr --conntype serial --connstring "dev=COM7,baud=115200,mtu=512" image upload build\firmware\zephyr\zephyr.signed.bin
mcumgr --conntype serial --connstring "dev=COM7,baud=115200,mtu=512" reset
```

핀 배치, 부트로더, 노드 CoAP 규격, 미해결 항목은 **[docs/build-and-flash.md](docs/build-and-flash.md)** 를 보세요.

게이트웨이(라즈베리파이 + nRF52840 RCP 동글 + CoAP↔웹서버 브리지) 구축·실행은 **[../gateway/README.md](../gateway/README.md)** 에 있습니다.

## 캘리브레이션 (요약)

캘리브레이션은 USB-C 셸에서 하고 결과는 NVS 에 저장됩니다. 재부팅·펌웨어 업데이트를 넘어 유지됩니다.

```
shelf show            # 현재 적용 중인 값
shelf raw             # 1회 측정 (raw 카운트 + mg)
shelf tare            # (선반 비우고) 현재 값을 영점으로 저장
shelf cal 1000        # (1000 g 추 올리고) counts_per_kg 계산 후 저장
shelf battery         # 배터리 전압/추정 잔량
shelf ledtest [n]     # LED 하드웨어 테스트 (숨쉬기 페이드)
shelf reset           # 공장 기본값으로 되돌리기
```

상세 절차와 주의사항(기구 변경 후 재캘리브레이션, stick-slip 드리프트 등)은 [docs/build-and-flash.md §8](docs/build-and-flash.md) 참고.

## 핀

| 기능 | 핀 | 비고 |
|---|---|---|
| LED | P0.06 | Q1(N채널) 게이트, `GPIO_ACTIVE_HIGH` |
| CS1237 DRDY/DOUT | P0.08 | 양방향 |
| CS1237 SCLK | P1.09 | |
| 로드셀 전원 스위치 | P0.12 | Q2(P채널 하이사이드), **`GPIO_ACTIVE_LOW`** |
| 배터리 전압 | (내부) | SAADC VDDH/5 탭, 외부 분압 없음 |
