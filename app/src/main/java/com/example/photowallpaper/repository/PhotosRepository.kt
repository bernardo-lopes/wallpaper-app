package com.example.photowallpaper.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.photowallpaper.api.DriveFile
import com.example.photowallpaper.api.GoogleDriveService
import com.example.photowallpaper.auth.GoogleAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class PhotosRepository(private val context: Context) {

    private val authManager = GoogleAuthManager(context)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor { message ->
            Log.d("OkHttp", message)
        }.apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    private val service: GoogleDriveService = Retrofit.Builder()
        .baseUrl(GoogleDriveService.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GoogleDriveService::class.java)

    private var cachedFiles: List<DriveFile> = emptyList()
    private var cachedFolderName: String? = null

    private suspend fun <T> withToken(block: suspend (String) -> T): T {
        var token = authManager.getAccessToken() ?: throw IllegalStateException("Not signed in")
        return try {
            block(token)
        } catch (e: HttpException) {
            if (e.code() == 401 || e.code() == 403) {
                Log.d("PhotosRepository", "Auth error ${e.code()}, invalidating and retrying...")
                authManager.invalidateToken(token)
                token = authManager.getAccessToken() ?: throw e
                block(token)
            } else {
                throw e
            }
        }
    }

    /**
     * Find the folder ID by name. Searches in the root of the user's Drive.
     */
    private suspend fun findFolderId(folderName: String): String? = withToken { token ->
        val query = "name = '$folderName' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val response = service.listFiles(
            auth = "Bearer $token",
            query = query,
            fields = "files(id,name)"
        )
        response.files?.firstOrNull()?.id
    }

    /**
     * Fetch all image files from the specified Google Drive folder.
     */
    suspend fun fetchPhotos(folderName: String, maxPages: Int = 5): List<DriveFile> = withContext(Dispatchers.IO) {
        val folderId = findFolderId(folderName)
            ?: throw IllegalStateException("Folder '$folderName' not found in Google Drive. Please create it and add photos.")

        val allFiles = mutableListOf<DriveFile>()
        var pageToken: String? = null

        try {
            repeat(maxPages) {
                val query = "'$folderId' in parents and mimeType contains 'image/' and trashed = false"
                val response = withToken { token ->
                    service.listFiles(
                        auth = "Bearer $token",
                        query = query,
                        pageSize = 100,
                        pageToken = pageToken
                    )
                }

                response.files?.let { allFiles.addAll(it) }
                pageToken = response.nextPageToken
                if (pageToken == null) return@repeat
            }
        } catch (e: HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            Log.e("PhotosRepository", "HTTP Error ${e.code()}: $errorBody", e)
            throw e
        } catch (e: Exception) {
            Log.e("PhotosRepository", "Error fetching photos", e)
            throw e
        }

        // Distinct by ID to avoid LazyColumn crashes
        val distinctFiles = allFiles.distinctBy { it.id }
        cachedFiles = distinctFiles
        cachedFolderName = folderName
        Log.d("PhotosRepository", "Found ${distinctFiles.size} images in folder '$folderName'")
        distinctFiles
    }

    /**
     * Pick a random image from the cached list and download it as a Bitmap.
     */
    suspend fun getRandomPhotoBitmap(folderName: String): Bitmap? = withContext(Dispatchers.IO) {
        // Refresh cache if empty or folder changed
        if (cachedFiles.isEmpty() || cachedFolderName != folderName) {
            fetchPhotos(folderName)
        }

        if (cachedFiles.isEmpty()) return@withContext null

        val randomFile = cachedFiles.random()
        Log.d("PhotosRepository", "Selected random photo: ${randomFile.name} (${randomFile.id})")

        try {
            val responseBody = withToken { token ->
                service.downloadFile(
                    auth = "Bearer $token",
                    fileId = randomFile.id
                )
            }

            val bytes = responseBody.bytes()
            Log.d("PhotosRepository", "Downloaded ${bytes.size} bytes")

            // Decode with inSampleSize for memory efficiency on large images
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

            // Calculate sample size targeting ~1080px width
            val targetWidth = 1080
            options.inSampleSize = calculateInSampleSize(options, targetWidth)
            options.inJustDecodeBounds = false

            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            Log.e("PhotosRepository", "Error downloading image: ${randomFile.name}", e)
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, targetWidth: Int): Int {
        val width = options.outWidth
        var inSampleSize = 1
        if (width > targetWidth) {
            while (width / inSampleSize > targetWidth * 2) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * Fetch all image files from a folder by its ID directly.
     */
    suspend fun fetchPhotosByFolderId(folderId: String, maxPages: Int = 5): List<DriveFile> = withContext(Dispatchers.IO) {
        val allFiles = mutableListOf<DriveFile>()
        var pageToken: String? = null

        try {
            repeat(maxPages) {
                val query = "'$folderId' in parents and mimeType contains 'image/' and trashed = false"
                val response = withToken { token ->
                    service.listFiles(
                        auth = "Bearer $token",
                        query = query,
                        pageSize = 100,
                        pageToken = pageToken
                    )
                }

                response.files?.let { allFiles.addAll(it) }
                pageToken = response.nextPageToken
                if (pageToken == null) return@repeat
            }
        } catch (e: HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            Log.e("PhotosRepository", "HTTP Error ${e.code()}: $errorBody", e)
            throw e
        }

        val distinctFiles = allFiles.distinctBy { it.id }
        cachedFiles = distinctFiles
        Log.d("PhotosRepository", "Found ${distinctFiles.size} images in folder ID '$folderId'")
        distinctFiles
    }

    /**
     * Pick a random image from the cached list (by folder ID) and download it as a Bitmap.
     */
    suspend fun getRandomPhotoBitmapByFolderId(folderId: String): Bitmap? = withContext(Dispatchers.IO) {
        if (cachedFiles.isEmpty()) {
            fetchPhotosByFolderId(folderId)
        }

        if (cachedFiles.isEmpty()) return@withContext null

        val randomFile = cachedFiles.random()
        Log.d("PhotosRepository", "Selected random photo: ${randomFile.name} (${randomFile.id})")

        try {
            val responseBody = withToken { token ->
                service.downloadFile(
                    auth = "Bearer $token",
                    fileId = randomFile.id
                )
            }

            val bytes = responseBody.bytes()
            Log.d("PhotosRepository", "Downloaded ${bytes.size} bytes")

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

            val targetWidth = 1080
            options.inSampleSize = calculateInSampleSize(options, targetWidth)
            options.inJustDecodeBounds = false

            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            Log.e("PhotosRepository", "Error downloading image: ${randomFile.name}", e)
            null
        }
    }

    /**
     * List subfolders inside a given parent folder.
     * Pass null for parentFolderId to list root-level folders.
     */
    suspend fun listFolders(parentFolderId: String? = null): List<DriveFile> = withContext(Dispatchers.IO) {
        val query = if (parentFolderId != null) {
            "'$parentFolderId' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        } else {
            "'root' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        }

        val allFolders = mutableListOf<DriveFile>()
        var pageToken: String? = null

        try {
            repeat(3) {
                val response = withToken { token ->
                    service.listFiles(
                        auth = "Bearer $token",
                        query = query,
                        fields = "files(id,name,mimeType),nextPageToken",
                        pageSize = 100,
                        pageToken = pageToken,
                        orderBy = "name"
                    )
                }
                response.files?.let { allFolders.addAll(it) }
                pageToken = response.nextPageToken
                if (pageToken == null) return@repeat
            }
        } catch (e: Exception) {
            Log.e("PhotosRepository", "Error listing folders", e)
            throw e
        }

        val distinctFolders = allFolders.distinctBy { it.id }
        Log.d("PhotosRepository", "Found ${distinctFolders.size} folders in parent=${parentFolderId ?: "root"}")
        distinctFolders
    }

    /**
     * Count image files in a folder (quick check without caching).
     */
    suspend fun countImagesInFolder(folderId: String): Int = withContext(Dispatchers.IO) {
        try {
            val query = "'$folderId' in parents and mimeType contains 'image/' and trashed = false"
            val response = withToken { token ->
                service.listFiles(
                    auth = "Bearer $token",
                    query = query,
                    fields = "files(id)",
                    pageSize = 1
                )
            }
            response.files?.size ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Download a thumbnail image for a DriveFile.
     * Thumbnails are small (~220px) and ideal for ML classification.
     * Returns null if the file has no thumbnail or download fails.
     */
    suspend fun downloadThumbnail(file: DriveFile): Bitmap? = withContext(Dispatchers.IO) {
        val rawUrl = file.thumbnailLink ?: return@withContext null
        // Request a larger thumbnail (480px) for better ML classification accuracy.
        // Google Drive thumbnail URLs end with "=sNNN"; replace or append the size param.
        val thumbnailUrl = if (rawUrl.contains("=s")) {
            rawUrl.replace(Regex("=s\\d+"), "=s480")
        } else {
            "$rawUrl=s480"
        }

        try {
            withToken { token ->
                val request = okhttp3.Request.Builder()
                    .url(thumbnailUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w("PhotosRepository", "Thumbnail download failed: ${response.code}")
                    return@withToken null
                }

                response.body?.byteStream()?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }
        } catch (e: Exception) {
            Log.w("PhotosRepository", "Error downloading thumbnail for ${file.name}", e)
            null
        }
    }

    /**
     * Pick a random image from cached files, optionally filtered to only files
     * whose detected labels overlap with [selectedLabels].
     *
     * @param matchingFileIds Set of file IDs that match the user's selected label filters.
     *                        Pass empty set to skip filtering (use all photos).
     */
    suspend fun getRandomPhotoBitmapFiltered(
        folderId: String?,
        folderName: String?,
        matchingFileIds: Set<String>
    ): Bitmap? = withContext(Dispatchers.IO) {
        // Refresh cache if needed
        if (cachedFiles.isEmpty()) {
            if (folderId != null) {
                fetchPhotosByFolderId(folderId)
            } else if (folderName != null) {
                fetchPhotos(folderName)
            }
        }

        val eligible = if (matchingFileIds.isNotEmpty()) {
            cachedFiles.filter { it.id in matchingFileIds }
        } else {
            cachedFiles
        }

        if (eligible.isEmpty()) return@withContext null

        val randomFile = eligible.random()
        Log.d("PhotosRepository", "Selected random photo: ${randomFile.name} (${randomFile.id}), filtered: ${matchingFileIds.isNotEmpty()}")

        try {
            val responseBody = withToken { token ->
                service.downloadFile(
                    auth = "Bearer $token",
                    fileId = randomFile.id
                )
            }

            val bytes = responseBody.bytes()
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            val targetWidth = 1080
            options.inSampleSize = calculateInSampleSize(options, targetWidth)
            options.inJustDecodeBounds = false
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            Log.e("PhotosRepository", "Error downloading image: ${randomFile.name}", e)
            null
        }
    }

    fun getCachedFiles(): List<DriveFile> = cachedFiles

    fun clearCache() {
        cachedFiles = emptyList()
        cachedFolderName = null
    }

    fun getPhotoCount(): Int = cachedFiles.size
}
