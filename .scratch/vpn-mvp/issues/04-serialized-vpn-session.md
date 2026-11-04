# 04: Последовательно управлять VPN-сессией

Status: ready-for-agent
Completion: implemented; real VPN and visual verification pending

**What to build:** подобно XRay Mini, обрабатывать команды и результаты фоновых операций через одного владельца состояния VPN-сессии. Сохранить существующий Android VPN, смену сервера и ручное восстановление.

## Acceptance criteria

- [x] Команды и события обрабатываются последовательно независимо от потока вызывающего кода; одновременные подключения принимают не более одного профиля.
- [x] Проверки, запуск и остановка не блокируют обработку команд. События старой сессии не изменяют состояние новой.
- [x] При отмене запуска или смены сервера новая сессия не стартует до окончания предыдущей операции и подтверждённой очистки. При ошибке очистки доступен ручной повтор.
- [x] Разрешение Android, отзыв разрешения, жизненный цикл сервиса и сохранённые признаки ошибки и очистки остаются согласованными.
- [x] Проверка интернета выполняется при подключении и повторяется через 30 секунд после окончания предыдущей. Успех или ошибка проверки не запускают и не останавливают VPN.
- [x] Тесты наблюдаемых команд и состояний покрывают параллельные команды, отмену, запоздалые результаты, ошибки очистки и повторные проверки; полноценная сборка и lint проходят.
- [ ] Реальный VPN, смена профиля, отключение и визуальные состояния проверены на Android Emulator, а недоступные проверки явно отмечены.

## Verification

- `./gradlew build --warning-mode all` — debug and release APKs, lint, 45 desktop tests and 45 Android host tests passed. Tests include concurrent connections, delayed permission and engine results, revocation during cleanup, periodic check recovery, and a non-cancellable old check after switch or disconnect.
- Two independent reviews found and verified fixes for stale permission, stale engine status, revocation during stop, foreground notification failure, and persistence ordering during failed cleanup.
- Final debug APK installed over the existing app; Android reported a successful activity launch and the stored subscription checksum remained unchanged. The emulator became unresponsive during UI automation and screenshot capture, so real VPN connection, switching, periodic network recovery, and final visual comparison were not confirmed. VPN remains disconnected and Wi-Fi is enabled.
- Pencil references inspected: Android Home disconnected `eiHex`, connected `tRJf1`, Profiles `a9FuU`; this change retains the existing MVP adaptations.
