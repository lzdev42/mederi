package xyz.mederi

expect fun currentTimeMillis(): Long

expect fun formatMessageTime(epochMillis: Long): String
