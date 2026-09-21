"""Create the bundled regular PI font from the SIL OFL Noto Sans SC variable TTF.

Usage: python tools/prepare-pi-font.py source-variable.ttf output-regular.ttf
Build dependency: fonttools==4.60.1 (not needed at application runtime).
"""
import argparse
import hashlib
from pathlib import Path
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

parser = argparse.ArgumentParser()
parser.add_argument("source", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
font = TTFont(args.source, checkChecksums=2)
font = instantiateVariableFont(font, {"wght": 400}, inplace=True)

# Noto shares glyphs between unified Han characters and their Kangxi radical
# compatibility forms. PDFBox reverses a shared glyph to the first cmap entry,
# producing radical codepoints when users copy standard Chinese text. Remove
# only duplicate radical aliases; keep fullwidth punctuation and all primary Han.
removed = set()
for cmap in font["cmap"].tables:
    if not cmap.isUnicode() or not hasattr(cmap, "cmap"):
        continue
    primary_glyphs = {glyph for code, glyph in cmap.cmap.items() if 0x3400 <= code <= 0x9FFF}
    for code, glyph in list(cmap.cmap.items()):
        if 0x2E80 <= code <= 0x2FDF and glyph in primary_glyphs:
            del cmap.cmap[code]
            removed.add(code)
font.save(args.output)
print(f"Removed {len(removed)} duplicate radical aliases")
print(f"Source SHA256: {hashlib.sha256(args.source.read_bytes()).hexdigest()}")
print(f"Output SHA256: {hashlib.sha256(args.output.read_bytes()).hexdigest()}")
