PI PDF embeds a regular-weight derivative of Noto Sans SC from the Noto CJK project (SIL Open Font License 1.1).

Source: https://github.com/notofonts/noto-cjk/blob/main/Sans/Variable/TTF/Subset/NotoSansSC-VF.ttf

See OFL.txt for the upstream license. Embedding and subsetting this font is permitted.
The Excel document names the same font; spreadsheet applications can substitute a locally available Chinese font.

The bundled `NotoSansSC-Regular.ttf` is built with `tools/prepare-pi-font.py` and FontTools 4.60.1.
The script fixes weight at 400 and removes duplicate radical aliases so PDF text extraction preserves standard Chinese characters.
It does not remove Chinese punctuation or primary Han characters. No proprietary system font is redistributed.

Upstream TTF SHA256: `d68bafcb48a2707749396aa12bbbd833cb70401f3a9a689fd2902c7e0d295964`
