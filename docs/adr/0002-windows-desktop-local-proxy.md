# Windows desktop follows X-Ray Mini's local proxy contract

The desktop client in this Kotlin Multiplatform project targets Windows 11 x64 first and reproduces X-Ray Mini's local SOCKS5/HTTP proxy behavior and Compact design. Android continues to use a system VPN. This avoids claiming system-wide Windows routing without a Windows VPN implementation, while letting the desktop client match the existing product; shared code is reused only where its behavior agrees on both platforms.
