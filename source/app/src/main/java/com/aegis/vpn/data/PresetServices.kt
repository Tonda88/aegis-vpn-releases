package com.aegis.vpn.data

data class PresetCategory(
    val id: String,
    val title: String,
    val description: String,
    val packages: Set<String>
)

object PresetServices {
    val categories = listOf(
        PresetCategory(
            id = "banks",
            title = "Банки",
            description = "Банковские приложения",
            packages = setOf(
                "ru.sberbankmobile",
                "ru.sberbank.sberbank",
                "com.idamob.tinkoff.android",
                "ru.tinkoff.investing",
                "ru.alfabank.mobile.android",
                "ru.vtb24.mobilebanking.android",
                "com.vtb.vtb",
                "ru.raiffeisennews",
                "ru.rosbank.rosbank",
                "ru.gazprombank.android.mobilebank",
                "ru.mts.mtbank",
                "com.bank.mts",
                "ru.psbank.mobile",
                "ru.openbank.mobile",
                "ru.sovcombank.mobile",
                "ru.homecredit.mobile"
            )
        ),
        PresetCategory(
            id = "government",
            title = "Госуслуги",
            description = "Государственные и налоговые сервисы",
            packages = setOf(
                "ru.gosuslugi.gosuslugi",
                "ru.rostel",
                "ru.esia.esia",
                "ru.fns.nalog",
                "ru.fns.lk",
                "ru.taximax.mobile",
                "com.minsvyaz.gosuslugi",
                "ru.mos.app",
                "ru.mos.ugd"
            )
        ),
        PresetCategory(
            id = "market",
            title = "Маркетплейсы",
            description = "Покупки и объявления",
            packages = setOf(
                "ru.ozon.app.android",
                "com.wildberries.ru",
                "ru.avito",
                "com.yandex.market",
                "ru.aliexpress.buyer",
                "com.alibaba.aliexpresshd",
                "ru.lamoda.android",
                "ru.dns.shop.android",
                "com.mvideo.android"
            )
        ),
        PresetCategory(
            id = "social",
            title = "Соцсети",
            description = "Мессенджеры и соцсети",
            packages = setOf(
                "org.telegram.messenger",
                "com.whatsapp",
                "com.instagram.android",
                "com.vkontakte.android",
                "ru.ok.android",
                "com.facebook.katana",
                "com.twitter.android",
                "com.snapchat.android",
                "com.discord",
                "com.google.android.youtube"
            )
        ),
        PresetCategory(
            id = "google",
            title = "Google",
            description = "Сервисы Google и Play",
            packages = setOf(
                "com.google.android.gms",
                "com.google.android.gsf",
                "com.android.vending",
                "com.google.android.gm",
                "com.google.android.apps.photos",
                "com.google.android.apps.maps",
                "com.google.android.youtube",
                "com.google.android.apps.docs"
            )
        )
    )
}
