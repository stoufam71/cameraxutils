package com.jds.camerax

import android.Manifest
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.animation.doOnCancel
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jds.camerax.databinding.VideoLayoutBinding
import com.jds.camerax.utils.SharedPrefsManager
import com.jds.camerax.utils.rotateButton
import com.jds.camerax.utils.toggleButton
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.properties.Delegates

@ExperimentalCamera2Interop
class VideoXUtils : BottomSheetDialogFragment() {

    private lateinit var _binding: VideoLayoutBinding
    private val binding get() = _binding

    // An instance of a helper function to work with Shared Preferences
    private val prefs by lazy { SharedPrefsManager.newInstance(requireContext()) }

    private lateinit var cameraExecutor: ExecutorService
    private val imageFolder = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        "CameraXImage"
    )
    private lateinit var viewFinder: PreviewView
    private lateinit var captureButton: View

    private var camera: Camera? = null
    private var preview: Preview? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null

    private var lensFacing = CameraSelector.DEFAULT_BACK_CAMERA

    // Selector showing which flash mode is selected (on, off or auto)
    private var flashMode by Delegates.observable(ImageCapture.FLASH_MODE_OFF) { _, _, new ->
        binding.btnFlash.setImageResource(
            when (new) {
                ImageCapture.FLASH_MODE_ON -> com.jds.camerax.R.drawable.ic_flash_on
                else -> com.jds.camerax.R.drawable.ic_flash_off
            }
        )
    }

    // Selector showing is grid enabled or not
    private var hasGrid = false

    // Selector showing is flash enabled or not
    private var isTorchOn = false

    // Selector showing is recording currently active
    private var isRecording = false
    private val animateRecord by lazy {
        ObjectAnimator.ofFloat(binding.btnRecordVideo, View.ALPHA, 1f, 0.5f).apply {
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            doOnCancel { binding.btnRecordVideo.alpha = 1f }
        }
    }

    private var displayId = -1

    private lateinit var onResult: (Uri) -> Unit

    private lateinit var behavior: BottomSheetBehavior<FrameLayout>

    private fun getWindowHeight() = resources.displayMetrics.heightPixels

    override fun onStart() {
        super.onStart()
        //Get the bottom_sheet of the system
        val view: FrameLayout = dialog?.findViewById(R.id.design_bottom_sheet)!!
        //Set the view height
        view.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
        //Get behavior
        behavior = BottomSheetBehavior.from(view)
        //Set the pop-up height
        behavior.peekHeight = getWindowHeight()
        //Set the expanded state
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.isDraggable = false
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                    //dismiss()
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {}

        })
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = VideoLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        flashMode = prefs.getInt(KEY_FLASH, ImageCapture.FLASH_MODE_OFF)
        hasGrid = prefs.getBoolean(KEY_GRID, false)
        initViews()

        viewFinder = binding.viewFinder
        captureButton = binding.btnRecordVideo

        cameraExecutor = Executors.newSingleThreadExecutor()
        imageFolder.mkdirs()

        if (allPermissionsGranted()) {
            // Each time apps is coming to foreground the need permission check is being processed
            binding.viewFinder.let { vf ->
                vf.post {
                    // Setting current display ID
                    displayId = vf.display.displayId
                    startCamera()
                }
            }
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(),
                permissions.toTypedArray(),
                REQUEST_CODE_PERMISSIONS
            )
        }

        captureButton.setOnClickListener { recordVideo() }
        binding.btnSwitchCamera.setOnClickListener { toggleCamera() }
        binding.btnGrid.setOnClickListener { toggleGrid() }
        binding.btnFlash.setOnClickListener { selectFlash() }
    }

    /**
     * Create some initial states
     * */
    private fun initViews() {
        binding.btnGrid.setImageResource(if (hasGrid) com.jds.camerax.R.drawable.ic_grid_on else com.jds.camerax.R.drawable.ic_grid_off)
        binding.groupGridLines.visibility = if (hasGrid) View.VISIBLE else View.GONE
        //adjustInsets()
    }


    override fun onDestroy() {
        super.onDestroy()
        camera?.cameraControl?.enableTorch(false)
        cameraExecutor.shutdown()
    }

    /**
     * Change the facing of camera
     *  toggleButton() function is an Extension function made to animate button rotation
     * */
    private fun toggleCamera() {
        binding.btnSwitchCamera.rotateButton(
            flag = lensFacing == CameraSelector.DEFAULT_BACK_CAMERA,
        ) {
            lensFacing = if (it) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }

            startCamera()
        }
    }

    /**
     * Turns on or off the grid on the screen
     * */
    private fun toggleGrid() {
        binding.btnGrid.toggleButton(
            flag = hasGrid,
            rotationAngle = 180f,
            firstIcon = com.jds.camerax.R.drawable.ic_grid_off,
            secondIcon = com.jds.camerax.R.drawable.ic_grid_on,
        ) { flag ->
            hasGrid = flag
            prefs.putBoolean(KEY_GRID, flag)
            binding.groupGridLines.visibility = if (flag) View.VISIBLE else View.GONE
        }
    }

    /**
     * Change flash mode to on and off respectively
     * */

    private fun selectFlash() {

        when (flashMode) {

            ImageCapture.FLASH_MODE_OFF -> {
                flashMode = ImageCapture.FLASH_MODE_ON
                isTorchOn = true
            }

            ImageCapture.FLASH_MODE_ON -> {
                flashMode = ImageCapture.FLASH_MODE_OFF
                isTorchOn = false
            }

            else -> {
                flashMode = ImageCapture.FLASH_MODE_OFF
                isTorchOn = false
                Log.e(TAG, "Flash mode unknown")
            }
        }

        camera?.cameraControl?.enableTorch(isTorchOn)
    }

    private fun startCamera() {
        viewFinder = binding.viewFinder

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({

            try {
                cameraProvider = cameraProviderFuture.get()
            } catch (e: InterruptedException) {
                Toast.makeText(requireContext(), "Error starting camera", Toast.LENGTH_SHORT).show()
                return@addListener
            } catch (e: ExecutionException) {
                Toast.makeText(requireContext(), "Error starting camera", Toast.LENGTH_SHORT).show()
                return@addListener
            }

            // The display rotation
            val rotation = viewFinder.display.rotation

            val localCameraProvider = cameraProvider
                ?: throw IllegalStateException("Camera initialization failed.")

            preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val cameraInfo = localCameraProvider.availableCameraInfos.filter {
                Camera2CameraInfo
                    .from(it)
                    .getCameraCharacteristic(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
            }

            val supportedQualities = QualitySelector.getSupportedQualities(cameraInfo[0])
            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
            )
            val recorder = Recorder.Builder()
                .setExecutor(ContextCompat.getMainExecutor(requireContext()))
                .setQualitySelector(qualitySelector)
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            localCameraProvider.unbindAll() // unbind the use-cases before rebinding them

            try {
                // Bind all use cases to the camera with lifecycle
                camera = localCameraProvider.bindToLifecycle(
                    viewLifecycleOwner, // current lifecycle owner
                    lensFacing, // either front or back facing
                    preview, // camera preview use case
                    videoCapture, // video capture use case
                )

                // Attach the viewfinder's surface provider to preview use case
                preview?.setSurfaceProvider(viewFinder.surfaceProvider)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind use cases", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    var recording: Recording? = null

    @SuppressLint("MissingPermission")
    private fun recordVideo() {
        if (recording != null) {
            animateRecord.cancel()
            recording?.stop()
        }
        val name = "video-${System.currentTimeMillis()}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
        }
        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            requireContext().contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
            .setContentValues(contentValues)
            .build()
        recording = videoCapture?.output
            ?.prepareRecording(requireContext(), mediaStoreOutput)
            ?.withAudioEnabled()
            ?.start(ContextCompat.getMainExecutor(requireContext())) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        animateRecord.start()
                    }

                    is VideoRecordEvent.Finalize -> {
                        if (!event.hasError()) {
                            val msg = "Video capture succeeded: " +
                                    "${event.outputResults.outputUri}"
                            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT)
                                .show()
                            Log.d(TAG, msg)
                            setCameraResult(event.outputResults.outputUri)
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(
                                TAG, "Video capture ends with error: " +
                                        "${event.error}"
                            )
                        }
                    }
                }
            }
        isRecording = !isRecording
    }


    private fun setCameraResult(fileUri: Uri) {
        onResult.invoke(fileUri)
        requireActivity().runOnUiThread {
            behavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
    }

    private fun showToast(text: String) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }

    private val permissions = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
    }

    private fun allPermissionsGranted() = permissions.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val TAG = "CameraUtils"
        private const val REQUEST_CODE_PERMISSIONS = 10
        const val KEY_FLASH = "sPrefFlashCamera"
        const val KEY_GRID = "sPrefGridCamera"

        fun newInstance(onResult: (Uri) -> Unit): VideoXUtils {
            return VideoXUtils().apply {
                this.onResult = onResult
            }
        }
    }
}