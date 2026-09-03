package com.cobaltloom.loraviewer.data.remote

sealed class TrailRouteApiException(message: String) : Exception(message) {
    class InvalidBaseUrl :
        TrailRouteApiException("サーバーURLが正しくありません。設定を確認してください。")

    class InvalidResponse :
        TrailRouteApiException("サーバーからの応答を解析できませんでした。")

    class ServerError(serverMessage: String) :
        TrailRouteApiException("サーバーエラー: $serverMessage")
}
