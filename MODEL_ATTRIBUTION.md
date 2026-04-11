# Model Attribution

This repository includes support for an experimental manga panel detection model that is fetched separately during local setup and release packaging.

## Panel Detection Model

- Model: `leoxs22/manga-panel-detector-yolo26n`
- Source: <https://huggingface.co/leoxs22/manga-panel-detector-yolo26n>
- Local asset filename: `data/src/main/assets/panel_detector/model.tflite`

If you use this model, please cite:

```bibtex
@misc{leoxs22_manga_panel_detector_2026,
    author={Leandro Narosky},
    title={{Manga Panel and Text Detector (YOLO26-nano)}},
    year={2026},
    publisher={Hugging Face},
    url={https://huggingface.co/leoxs22/manga-panel-detector-yolo26n}
}
```

## Dataset Attribution

This model was trained on Manga109-s. Please cite:

```bibtex
@article{multimedia_aizawa_2020,
    author={Kiyoharu Aizawa and Azuma Fujimoto and Atsushi Otsubo and Toru Ogawa and Yusuke Matsui and Koki Tsubota and Hikaru Ikuta},
    title={Building a Manga Dataset ``Manga109'' with Annotations for Multimedia Applications},
    journal={IEEE MultiMedia},
    volume={27},
    number={2},
    pages={8--18},
    doi={10.1109/mmul.2020.2987895},
    year={2020}
}

@article{mtap_matsui_2017,
    author={Yusuke Matsui and Kota Ito and Yuji Aramaki and Azuma Fujimoto and Toru Ogawa and Toshihiko Yamasaki and Kiyoharu Aizawa},
    title={Sketch-based Manga Retrieval using Manga109 Dataset},
    journal={Multimedia Tools and Applications},
    volume={76},
    number={20},
    pages={21811--21838},
    doi={10.1007/s11042-016-4020-z},
    year={2017}
}
```

## Notes

- The panel detector model binary is not committed to git.
- Contributors should follow the setup instructions in [CONTRIBUTING.md](./CONTRIBUTING.md) when placing the local asset.
