The Android app keeps separate model selections for **four-player** and **three-player** games. Both start with their bundled Mortal model. Importing or restoring one mode leaves the other mode's selection unchanged. The app automatically uses the matching selection when a game starts, and keeps both choices after closing or updating the app.

**Convert a Mortal checkpoint once.** Android executes an exported model; a `.pth` training checkpoint needs conversion before import. The conversion and its validation run in GitHub Actions. The phone then runs the converted model locally, without an inference server or a new APK build.

1. Put the checkpoint at a direct HTTPS download URL, such as the original model publisher's download link or a download you control. Use a model you trust. The converter downloads the checkpoint onto a GitHub runner; avoid putting private download credentials in a public workflow input.
2. Run [Android model conversion](https://github.com/Tsuki321/Akagi-NG/actions/workflows/android-model.yml) on the `android/fullscreen-app` branch. Select `4` or `3`, give the model a name, enter its checkpoint URL, and optionally provide its SHA-256 checksum.
3. Download the successful run's `Mortal-4p-Android-model` or `Mortal-3p-Android-model` artifact. Extract the artifact ZIP to get `Mortal-4p.akagimodel` or `Mortal-3p.akagimodel`. Keep the `.akagimodel` file intact.
4. In Akagi, tap the advice chip, open settings, and tap **Import model** under **Four-player model** or **Three-player model**. Choose the converted file. The selected model's name appears after the phone finishes validation.

Only convert the mode you want to change. A four-player replacement does not require a three-player checkpoint, and a three-player replacement does not require a four-player checkpoint. Each custom selection has its own **Use bundled model** button. Cancelling the picker or importing an invalid file keeps the current selections.

On branches where a newly added workflow is not yet listed in GitHub's Actions sidebar, dispatch it from an authenticated GitHub CLI with the branch specified:

```powershell
gh workflow run android-model.yml --ref android/fullscreen-app -f players=4 -f model_name="My four-player model" -f checkpoint_url="https://example.com/my-mortal.pth"
```

Replace the example URL with the actual direct download. Use `players=3` for a three-player model. The workflow accepts one checkpoint per run.

**Supported checkpoints.** The converter supports Mortal v4 DQN checkpoints with `mortal`, `current_dqn`, and the matching `config`. Four-player models use 1012 observation channels and 46 actions; three-player models use this app's validated legacy 775-channel layout and 44 actions. A matching tensor shape alone does not establish compatibility with a different encoder. Other model versions, policy/GroupNorm model families, and alternate sanma encoders need a separate compatible conversion. A checkpoint can be at most 1 GiB, and its exported graph at most 512 MiB; larger networks still need adequate memory and time on the phone.

The converter loads weights with PyTorch's restricted `weights_only=True` loader, builds the repository's exact network, verifies native observations and legal actions against the desktop engine, and compares ONNX outputs and selected actions with that checkpoint's PyTorch outputs. A wrong-mode or incompatible checkpoint fails conversion. The output contains the model, mode and encoder metadata, checksums, and reference scores belonging to that checkpoint.

On import, Akagi copies the bundle into private app storage, validates its metadata and checksums, and runs its reference cases on the phone's CPU before committing the selected mode. The reference inputs come from the app's trusted observation fixtures. Copying and candidate checks use a separate worker so live capture can continue. The selection is committed atomically; the current game state is retained, and any pending advice for the changed mode is recomputed with the new model. The app keeps only the active inference model loaded. **Check local model** validates each selected model against its own reference scores, including replacements.

Android's document picker grants access only to the selected file, and the app copies it locally; no broad storage permission is required. The direct-buffer ONNX loading path avoids a full Java-heap copy of a large replacement graph. See the [Android document-picker contract](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.OpenDocument), [atomic-file API](https://developer.android.com/reference/android/util/AtomicFile), and [ONNX Runtime Java session API](https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtEnvironment.html).
