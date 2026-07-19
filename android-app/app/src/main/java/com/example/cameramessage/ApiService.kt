package com.example.cameramessage

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * Smart Chemical Shelf FastAPI 서버와 통신하는 인터페이스.
 * 엔드포인트 계약은 web/routes/ 의 FastAPI 라우터와 1:1 대응합니다.
 */
interface ApiService {

    // --- Users ---
    @GET("api/users")
    suspend fun getUsers(): List<UserData>

    @POST("api/users/register")
    suspend fun registerUser(@Body request: UserRegisterRequest): UserData

    @POST("api/users/login")
    suspend fun login(@Body request: UserLoginRequest): UserData

    // --- Shelves ---
    @GET("api/shelves")
    suspend fun getShelves(): List<ShelfDevice>

    @GET("api/shelves/configs")
    suspend fun getShelfConfigs(): List<ShelfConfig>

    @POST("api/shelves/configs/{configId}")
    suspend fun updateShelfConfig(
        @Path("configId") configId: String,
        @Body request: Map<String, Int>
    ): ShelfConfig

    @POST("api/shelves/register/{shelfId}")
    suspend fun registerShelf(
        @Path("shelfId") shelfId: String,
        @Body request: ShelfRegister
    ): ShelfDevice

    @POST("api/shelves/{shelfId}/weight")
    suspend fun updateShelfWeight(
        @Path("shelfId") shelfId: String,
        @Body request: ShelfWeightUpdate
    ): ShelfWeightResponse

    @POST("api/shelves/{shelfId}/led")
    suspend fun setLedState(
        @Path("shelfId") shelfId: String,
        @Body request: ShelfLedUpdate
    ): ShelfDevice

    // --- Chemicals ---
    @GET("api/chemicals")
    suspend fun getChemicals(): List<ChemicalData>

    @GET("api/chemicals/ocr-chemicals")
    suspend fun getOcrChemicals(): List<String>

    @POST("api/chemicals/scan-in")
    suspend fun scanIn(@Body request: ScanInRequest): ScanInResponse

    @POST("api/chemicals/scan-out")
    suspend fun scanOut(@Body request: ScanOutRequest): ScanOutResponse

    @POST("api/chemicals/select-led")
    suspend fun selectLed(@Body request: SelectLedRequest): SelectLedResponse

    @GET("api/chemicals/alerts")
    suspend fun getAlerts(): ChemicalAlerts

    // --- Logs ---
    @GET("api/logs")
    suspend fun getLogs(): List<TransactionLog>

    // --- Check-in Session ---
    @GET("api/checkin-session")
    suspend fun getCheckinSession(): CheckinSessionState

    @POST("api/checkin-session/cancel")
    suspend fun cancelCheckinSession(): Map<String, String>

    // --- Legacy (구버전 LED 데모 호환) ---
    @GET("api/led")
    suspend fun getLed(): LedState

    @PUT("api/led/{led_id}")
    suspend fun setLed(
        @Path("led_id") ledId: String,
        @Body command: LedCommand
    ): LedState
}
