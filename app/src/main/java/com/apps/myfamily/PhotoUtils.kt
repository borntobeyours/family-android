package com.apps.myfamily

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object PhotoUtils {
    private const val TAG = "PhotoUtils"
    
    /**
     * Takes a photo without UI and uploads it to a specified URL
     * @param context Application context
     * @param cameraFacing "front" or "back" to specify which camera to use
     * @param uploadUrl URL to upload the photo to
     */
    fun takePhotoAndUpload(context: Context, cameraFacing: String, uploadUrl: String) {
        Log.d(TAG, "Starting photo capture and upload process with camera: $cameraFacing, uploadUrl: $uploadUrl")
        val executor = Executors.newSingleThreadExecutor()
        Log.d(TAG, "Initializing CameraProvider")
        val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> =
            ProcessCameraProvider.getInstance(context)
            
        // Select camera based on facing parameter
        val lensFacing = when (cameraFacing.lowercase()) {
            "front" -> CameraSelector.LENS_FACING_FRONT
            else -> CameraSelector.LENS_FACING_BACK
        }
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()
            
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                
                // Configure image capture use case
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                    
                // Unbind all use cases before binding
                cameraProvider.unbindAll()
                
                // Create a placeholder preview (required for binding)
                val preview = Preview.Builder().build()
                val dummySurfaceProvider = Preview.SurfaceProvider { }
                preview.setSurfaceProvider(dummySurfaceProvider)
                
                // Bind use cases to camera
                Log.d(TAG, "Binding camera use cases")
                val camera = cameraProvider.bindToLifecycle(
                    ContextWrapperLifecycle(context),
                    cameraSelector,
                    preview,
                    imageCapture
                )
                
                // Create output file in cache directory
                val photoFile = File(
                    context.cacheDir,
                    "photo_${System.currentTimeMillis()}.jpg"
                )
                Log.d(TAG, "Creating output file: ${photoFile.absolutePath}")
                
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                
                // Take the picture
                imageCapture.takePicture(
                    outputOptions,
                    executor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            Log.d(TAG, "Photo saved to: ${output.savedUri?.path ?: photoFile.absolutePath}")
                            Log.i(TAG, "Photo taken and saved to ${photoFile.absolutePath}")
                            
                            try {
                                // Compress the image
                                Log.d(TAG, "Compressing image: ${photoFile.name}")
                                val compressedFile = compressImage(photoFile)
                                
                                // Upload the compressed file
                                uploadFileToBackend(context, compressedFile, uploadUrl)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing image: ${e.message}", e)
                            }
                        }
                        
                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "Error taking photo: ${exception.message}", exception)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up camera: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
    
    /**
     * Uploads a file to the specified URL
     * @param context Application context
     * @param file The file to upload
     * @param uploadUrl URL to upload the file to
     */
    private fun uploadFileToBackend(context: Context, file: File, uploadUrl: String) {
        try {
            Log.d(TAG, "Initiating upload to: $uploadUrl for file: ${file.name}")
            // Create multipart request body
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "image", 
                    file.name, 
                    file.asRequestBody("image/jpeg".toMediaType())
                )
                .build()
            
            // Build the request
            val request = Request.Builder()
                .url(uploadUrl)
                .post(requestBody)
                .build()
            
            // Create OkHttp client with reasonable timeouts
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            
            // Execute the request asynchronously
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Upload failed: ${e.message}", e)
                }
                
                override fun onResponse(call: Call, response: Response) {
                    Log.d(TAG, "Upload response: ${response.code} for file: ${file.name}")
                    if (response.isSuccessful) {
                        Log.i(TAG, "Upload successful to $uploadUrl, response code: ${response.code}")
                    } else {
                        Log.e(TAG, "Upload unsuccessful: ${response.body?.string()}")
                    }
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error during upload: ${e.message}", e)
        }
    }
    
    /**
     * Compresses an image file
     * @param file The original image file
     * @return A new File with the compressed image
     */
    fun compressImage(file: File): File {
        try {
            // Decode the image
            val options = BitmapFactory.Options().apply {
                inSampleSize = 2  // Sample size for downscaling
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            
            // Create output file
            val compressedFile = File(
                file.parentFile,
                "compressed_${file.name}"
            )
            
            // Compress and save
            val outputStream = FileOutputStream(compressedFile)
            ByteArrayOutputStream().use { byteArrayOutputStream ->
                // Compress to JPEG with 80% quality
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream)
                
                // Write to file
                outputStream.write(byteArrayOutputStream.toByteArray())
                outputStream.flush()
            }
            outputStream.close()
            
            Log.i(TAG, "Image compressed: original size=${file.length()}, compressed size=${compressedFile.length()}")
            
            return compressedFile
        } catch (e: Exception) {
            Log.e(TAG, "Error compressing image: ${e.message}", e)
            // Return original file if compression fails
            return file
        }
    }
}