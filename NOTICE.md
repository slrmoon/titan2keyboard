# Third-party data

## Word frequencies — wordfreq

`app/src/main/assets/dict/*.tsv.gz` are derived from [wordfreq](https://github.com/rspeer/wordfreq)
by Robyn Speer and contributors, which is licensed under CC BY-SA 4.0. The word lists were
filtered (alphabet, length, common misspellings removed with the help of
[pymorphy3](https://github.com/no-plagiarism/pymorphy3)) by `tools/build_dictionary.py`.
These files are therefore distributed under CC BY-SA 4.0.

## Layout format

Layout JSON files use the format of [Pastiera](https://github.com/palsoftware/pastiera) so that its
layouts and its web editor can be used. The bundled layouts were written for this project.
