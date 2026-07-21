# ShareWave Icons

The `sharewave.png` (32×32) in this directory is the base icon.

`package-gui.sh` uses it directly on Linux. For macOS and Windows you need
platform-specific formats. If you don't create them, the script falls back to
the PNG, but the app may look lower-quality on those platforms.

## Creating sharewave.icns (macOS)

On a Mac, using the `iconutil` command that ships with Xcode:

```bash
# Create an iconset directory with all required sizes
mkdir -p sharewave.iconset
sips -z 16   16   sharewave.png --out sharewave.iconset/icon_16x16.png
sips -z 32   32   sharewave.png --out sharewave.iconset/icon_16x16@2x.png
sips -z 32   32   sharewave.png --out sharewave.iconset/icon_32x32.png
sips -z 64   64   sharewave.png --out sharewave.iconset/icon_32x32@2x.png
sips -z 128  128  sharewave.png --out sharewave.iconset/icon_128x128.png
sips -z 256  256  sharewave.png --out sharewave.iconset/icon_128x128@2x.png
sips -z 256  256  sharewave.png --out sharewave.iconset/icon_256x256.png
sips -z 512  512  sharewave.png --out sharewave.iconset/icon_256x256@2x.png
sips -z 512  512  sharewave.png --out sharewave.iconset/icon_512x512.png
sips -z 1024 1024 sharewave.png --out sharewave.iconset/icon_512x512@2x.png
iconutil -c icns sharewave.iconset -o sharewave.icns
rm -rf sharewave.iconset
```

## Creating sharewave.ico (Windows)

On Linux (with ImageMagick):
```bash
convert sharewave.png -define icon:auto-resize=256,128,64,48,32,16 sharewave.ico
```

On Windows (with ImageMagick for Windows, or use any icon editor):
```powershell
magick sharewave.png -define icon:auto-resize=256,128,64,48,32,16 sharewave.ico
```

Or use an online converter such as https://convertio.co/png-ico/

## Replacing with a better icon

Replace `sharewave.png` with a high-quality 512×512 (or larger) PNG of your
own design — `package-gui.sh` will use it automatically. The `.icns` and
`.ico` files can then be regenerated from it using the commands above.
