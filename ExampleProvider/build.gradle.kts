// use an integer for version numbers
version = 21

cloudstream {
    //language = "en"
    // All of these properties are optional, you can safely remove them

    description = "Contenido en Latino"
    authors = listOf("Ranita, Yeji", "Latino")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )

    iconUrl = "https://www.allkpop.com/upload/2025/03/content/160548/1742118503-0001838770-001-20250316163706520.jpg"
}
