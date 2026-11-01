#!/bin/sh
# SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
# SPDX-License-Identifier: GPL-3.0-only

# Installed at /usr/bin/kalita — same LD_LIBRARY_PATH/QT_PLUGIN_PATH logic as the AppImage's own
# AppRun (modules/app-desktop/build-logic's assembleAppDir), just with the fixed install path
# instead of AppRun's self-relative $HERE (a .deb's install location is not relocatable).
HERE=/usr/lib/kalita
export LD_LIBRARY_PATH="$HERE/usr/lib:$LD_LIBRARY_PATH"
export QT_PLUGIN_PATH="$HERE/usr/lib/qt-plugins"
exec "$HERE/usr/bin/Kalita" "$@"
