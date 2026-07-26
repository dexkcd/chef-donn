package ph.chefdonn.hub

import ph.chefdonn.domain.MenuItem
import ph.chefdonn.domain.TableInfo

/** Starter menu for demos and first boot; owners edit it in the admin screen. */
object SeedData {

    val tables: List<TableInfo> = (1..10).map { TableInfo("t$it", "T$it") }

    val menu: List<MenuItem> = listOf(
        MenuItem("m-sisig", "Sizzling Sisig", 28000, "GRILL", "Mains"),
        MenuItem("m-liempo", "Inihaw na Liempo", 30000, "GRILL", "Mains"),
        MenuItem("m-chicken-bbq", "Chicken BBQ (2 sticks)", 18000, "GRILL", "Mains"),
        MenuItem("m-kare-kare", "Kare-Kare", 38000, "MAINS", "Mains"),
        MenuItem("m-sinigang", "Sinigang na Baboy", 32000, "MAINS", "Mains"),
        MenuItem("m-adobo", "Chicken Adobo", 25000, "MAINS", "Mains"),
        MenuItem("m-crispy-pata", "Crispy Pata", 52000, "FRY", "Mains"),
        MenuItem("m-lumpia", "Lumpiang Shanghai", 16000, "FRY", "Starters"),
        MenuItem("m-calamares", "Calamares", 19000, "FRY", "Starters"),
        MenuItem("m-rice", "Plain Rice", 3500, "MAINS", "Rice"),
        MenuItem("m-garlic-rice", "Garlic Rice", 5000, "MAINS", "Rice"),
        MenuItem("m-halo-halo", "Halo-Halo", 15000, "DESSERT", "Dessert"),
        MenuItem("m-leche-flan", "Leche Flan", 9000, "DESSERT", "Dessert"),
        MenuItem("m-coke", "Coke (in can)", 7000, "DRINKS", "Drinks"),
        MenuItem("m-iced-tea", "House Iced Tea", 8500, "DRINKS", "Drinks"),
        MenuItem("m-san-mig", "San Miguel Pale Pilsen", 9500, "DRINKS", "Drinks"),
    )
}
