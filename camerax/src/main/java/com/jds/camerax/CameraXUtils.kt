package com.jds.camerax

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.LayoutInflater
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.material.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jds.camerax.analyzer.LuminosityAnalyzer
import com.jds.camerax.databinding.CameraLayoutBinding
import com.jds.camerax.enums.CameraTimer
import com.jds.camerax.utils.SharedPrefsManager
import com.jds.camerax.utils.ThreadExecutor
import com.jds.camerax.utils.rotateButton
import com.jds.camerax.utils.toggleButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.properties.Delegates

class CameraXUtils : BottomSheetDialogFragment() {

    private lateinit var _binding: CameraLayoutBinding
    private val binding get() = _binding

    // An instance of a helper function to work with Shared Preferences
    private val prefs by lazy { SharedPrefsManager.newInstance(requireContext()) }

    private lateinit var cameraExecutor: ExecutorService
    private val imageFolder = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        "CameraXImage"
    )

    private var preview: Preview? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null

    private var lensFacing = CameraSelector.DEFAULT_BACK_CAMERA
    private var hdrCameraSelector: CameraSelector? = null

    // Selector showing which flash mode is selected (on, off or auto)
    private var flashMode by Delegates.observable(ImageCapture.FLASH_MODE_OFF) { _, _, new ->
        binding.btnFlash.setImageResource(
            when (new) {
                ImageCapture.FLASH_MODE_ON -> com.jds.camerax.R.drawable.ic_flash_on
                ImageCapture.FLASH_MODE_AUTO -> com.jds.camerax.R.drawable.ic_flash_auto
                else -> com.jds.camerax.R.drawable.ic_flash_off
            }
        )
    }

    // Selector showing is grid enabled or not
    private var hasGrid = false

    // Selector showing is hdr enabled or not (will work, only if device's camera supports hdr on hardware level)
    private var hasHdr = false

    // Selector showing is there any selected timer and it's value (3s or 10s)
    private var selectedTimer by Delegates.observable(CameraTimer.OFF) { _, _, new ->
        binding.btnTimer.setImageResource(
            when (new) {
                CameraTimer.S3 -> com.jds.camerax.R.drawable.ic_timer_3
                CameraTimer.S10 -> com.jds.camerax.R.drawable.ic_timer_10
                else -> com.jds.camerax.R.drawable.ic_timer_off
            }
        )
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
        _binding = CameraLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        flashMode = prefs.getInt(KEY_FLASH, ImageCapture.FLASH_MODE_OFF)
        hasGrid = prefs.getBoolean(KEY_GRID, false)
        hasHdr = prefs.getBoolean(KEY_HDR, false)
        initViews()

        cameraExecutor = Executors.newSingleThreadExecutor()
        imageFolder.mkdirs()

        imageCapture = ImageCapture.Builder()
            .setTargetRotation(Surface.ROTATION_0)
            .build()

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

        binding.btnTakePicture.setOnClickListener { takePicture() }
        binding.btnSwitchCamera.setOnClickListener { toggleCamera() }
        binding.btnGrid.setOnClickListener { toggleGrid() }
        binding.btnFlash.setOnClickListener { selectFlash() }
        binding.btnHdr.setOnClickListener { toggleHdr() }
        binding.btnExposure.setOnClickListener { binding.flExposure.visibility = View.VISIBLE }
        binding.flExposure.setOnClickListener { binding.flExposure.visibility = View.GONE }
        binding.btnTimer.setOnClickListener { selectTimer() }
    }

    /**
     * Create some initial states
     * */
    private fun initViews() {
        binding.btnGrid.setImageResource(if (hasGrid) com.jds.camerax.R.drawable.ic_grid_on else com.jds.camerax.R.drawable.ic_grid_off)
        binding.btnHdr.setImageResource(if (hasHdr) com.jds.camerax.R.drawable.ic_hdr_on else com.jds.camerax.R.drawable.ic_hdr_off)
        binding.groupGridLines.visibility = if (hasGrid) View.VISIBLE else View.GONE
        //adjustInsets()
    }

    override fun onDestroy() {
        super.onDestroy()
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
     * Turns on or off the HDR if available
     * */
    private fun toggleHdr() {
        binding.btnHdr.toggleButton(
            flag = hasHdr,
            rotationAngle = 360f,
            duration = 400,
            firstIcon = com.jds.camerax.R.drawable.ic_hdr_off,
            secondIcon = com.jds.camerax.R.drawable.ic_hdr_on,
        ) { flag ->
            hasHdr = flag
            prefs.putBoolean(KEY_HDR, flag)
            startCamera()
        }
    }

    /**
     * Change flash mode to on, auto, and off respectively
     * */

    private fun selectFlash() {

        when (imageCapture?.flashMode) {

            ImageCapture.FLASH_MODE_AUTO -> {
                flashMode = ImageCapture.FLASH_MODE_OFF
            }

            ImageCapture.FLASH_MODE_OFF -> {
                flashMode = ImageCapture.FLASH_MODE_ON
            }

            ImageCapture.FLASH_MODE_ON -> {
                flashMode = ImageCapture.FLASH_MODE_AUTO
            }

            else -> {
                flashMode = ImageCapture.FLASH_MODE_OFF
                Log.e(TAG, "Flash mode unknown")
            }
        }

        imageCapture?.flashMode = flashMode
        prefs.putInt(KEY_FLASH, flashMode)
    }

    private fun selectTimer() {
        selectedTimer = when (selectedTimer) {
            CameraTimer.OFF -> CameraTimer.S3
            CameraTimer.S3 -> CameraTimer.S10
            CameraTimer.S10 -> CameraTimer.OFF
        }
    }


    private fun startCamera() {
        val viewFinder = binding.viewFinder

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

            checkForHdrExtensionAvailability()

            // The Configuration of image analyzing
            imageAnalyzer = ImageAnalysis.Builder()
                //.setTargetAspectRatio(aspectRatio) // set the analyzer aspect ratio
                .setTargetRotation(rotation) // set the analyzer rotation
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // in our analysis, we care about the latest image
                .build()
                .also { setLuminosityAnalyzer(it) }

            // Unbind the use-cases before rebinding them
            localCameraProvider.unbindAll()

            // Bind all use cases to the camera with lifecycle
            bindToLifecycle(localCameraProvider, viewFinder)

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun setLuminosityAnalyzer(imageAnalysis: ImageAnalysis) {
        // Use a worker thread for image analysis to prevent glitches
        val analyzerThread = HandlerThread("LuminosityAnalysis").apply { start() }
        imageAnalysis.setAnalyzer(
            ThreadExecutor(Handler(analyzerThread.looper)),
            LuminosityAnalyzer()
        )
    }

    private fun bindToLifecycle(
        localCameraProvider: ProcessCameraProvider,
        viewFinder: PreviewView
    ) {
        try {
            val camera: Camera
            localCameraProvider.bindToLifecycle(
                this, // current lifecycle owner
                hdrCameraSelector ?: lensFacing, // either front or back facing
                preview, // camera preview use case
                imageCapture, // image capture use case
                imageAnalyzer, // image analyzer use case
            ).run {
                camera = this
                // Init camera exposure control
                cameraInfo.exposureState.run {
                    val lower = exposureCompensationRange.lower
                    val upper = exposureCompensationRange.upper

                    binding.sliderExposure.run {
                        valueFrom = lower.toFloat()
                        valueTo = upper.toFloat()
                        stepSize = 1f
                        value = exposureCompensationIndex.toFloat()

                        addOnChangeListener { _, value, _ ->
                            cameraControl.setExposureCompensationIndex(value.toInt())
                        }
                    }
                }
            }

            val scaleGestureDetector = ScaleGestureDetector(requireContext(),
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        val scale =
                            camera.cameraInfo.zoomState.value!!.zoomRatio * detector.scaleFactor
                        camera.cameraControl.setZoomRatio(scale)
                        return true
                    }
                })


            viewFinder.setOnTouchListener { view, event ->
                view.performClick()
                scaleGestureDetector.onTouchEvent(event)
                return@setOnTouchListener true
            }

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(viewFinder.surfaceProvider)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind use cases", e)
        }
    }

    @Suppress("NON_EXHAUSTIVE_WHEN")
    private fun takePicture() = lifecycleScope.launch(Dispatchers.Main) {
        // Show a timer based on user selection
        when (selectedTimer) {
            CameraTimer.S3 -> for (i in 3 downTo 1) {
                binding.tvCountDown.text = i.toString()
                delay(1000)
            }

            CameraTimer.S10 -> for (i in 10 downTo 1) {
                binding.tvCountDown.text = i.toString()
                delay(1000)
            }

            CameraTimer.OFF -> {}
        }
        binding.tvCountDown.text = ""
        captureImage()
    }

    private fun captureImage() {
        val file = createImageFile()
        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture?.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = outputFileResults.savedUri ?: file.toUri()
                    Log.e(TAG, "Image saved: $savedUri")
                    setCameraResult(savedUri)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Image capture failed: ${exception.message}", exception)
                    //showToast("Image capture failed.")
                }
            }
        )
    }

    private fun checkForHdrExtensionAvailability() {
        // Create a Vendor Extension for HDR
        val extensionsManagerFuture = ExtensionsManager.getInstanceAsync(
            requireContext(), cameraProvider ?: return,
        )
        extensionsManagerFuture.addListener(
            {
                val extensionsManager = extensionsManagerFuture.get() ?: return@addListener
                val cameraProvider = cameraProvider ?: return@addListener

                val isAvailable =
                    extensionsManager.isExtensionAvailable(lensFacing, ExtensionMode.HDR)

                // check for any extension availability
                println(
                    "AUTO " + extensionsManager.isExtensionAvailable(
                        lensFacing,
                        ExtensionMode.AUTO
                    )
                )
                println(
                    "HDR " + extensionsManager.isExtensionAvailable(
                        lensFacing,
                        ExtensionMode.HDR
                    )
                )
                println(
                    "FACE RETOUCH " + extensionsManager.isExtensionAvailable(
                        lensFacing,
                        ExtensionMode.FACE_RETOUCH
                    )
                )
                println(
                    "BOKEH " + extensionsManager.isExtensionAvailable(
                        lensFacing,
                        ExtensionMode.BOKEH
                    )
                )
                println(
                    "NIGHT " + extensionsManager.isExtensionAvailable(
                        lensFacing,
                        ExtensionMode.NIGHT
                    )
                )
                println(
                    "NONE " + extensionsManager.isExtensionAvailable(
                        lensFacing,
                        ExtensionMode.NONE
                    )
                )

                // Check if the extension is available on the device
                if (!isAvailable) {
                    // If not, hide the HDR button
                    binding.btnHdr.visibility = View.GONE
                } else if (hasHdr) {
                    // If yes, turn on if the HDR is turned on by the user
                    binding.btnHdr.visibility = View.VISIBLE
                    hdrCameraSelector =
                        extensionsManager.getExtensionEnabledCameraSelector(
                            lensFacing,
                            ExtensionMode.HDR
                        )
                }
            },
            ContextCompat.getMainExecutor(requireContext())
        )
    }

    private fun setCameraResult(imageUri: Uri) {
        onResult.invoke(imageUri)
        requireActivity().runOnUiThread {
            behavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
    }

    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.getDefault()
        ).format(System.currentTimeMillis())
        val imageFileName = "JPEG_$timeStamp"
        val storageDir = requireActivity().getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File(
            storageDir,
            "$imageFileName.jpg"
        )
    }

    private fun showToast(text: String) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }

    private val permissions = mutableListOf(
        Manifest.permission.CAMERA
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
        const val KEY_HDR = "sPrefHDR"

        fun newInstance(onResult: (Uri) -> Unit): CameraXUtils {
            return CameraXUtils().apply {
                this.onResult = onResult
            }
        }
    }
}