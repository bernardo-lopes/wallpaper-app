package com.example.photowallpaper.api

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

interface GoogleDriveService {

    /**
     * List files in Drive matching a query.
     * Example query: "'FOLDER_ID' in parents and mimeType contains 'image/'"
     */
    @GET("drive/v3/files")
    suspend fun listFiles(
        @Header("Authorization") auth: String,
        @Query("q") query: String,
        @Query("fields") fields: String = "files(id,name,mimeType,thumbnailLink,size),nextPageToken",
        @Query("pageSize") pageSize: Int = 100,
        @Query("pageToken") pageToken: String? = null,
        @Query("orderBy") orderBy: String? = null
    ): DriveFileListResponse

    /**
     * Download a file's content by ID.
     * Use alt=media to get the actual file bytes.
     */
    @GET("drive/v3/files/{fileId}?alt=media")
    suspend fun downloadFile(
        @Header("Authorization") auth: String,
        @Path("fileId") fileId: String
    ): ResponseBody

    companion object {
        const val BASE_URL = "https://www.googleapis.com/"
    }
}

@Serializable
data class DriveFileListResponse(
    val files: List<DriveFile>? = null,
    val nextPageToken: String? = null
)

@Serializable
data class DriveFile(
    val id: String,
    val name: String? = null,
    val mimeType: String? = null,
    val thumbnailLink: String? = null,
    val size: String? = null
)
