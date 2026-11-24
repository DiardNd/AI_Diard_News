package com.example.presentation.navigation

sealed class Screen(val route: String) {
    object Feed : Screen("feed")
    object Sources : Screen("sources")
    object Tags : Screen("tags")
    object Help : Screen("help")
    data class Detail(val articleId: Long) : Screen("detail")
}
