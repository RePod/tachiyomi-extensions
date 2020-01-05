package eu.kanade.tachiyomi.extension.all.mmrcms

import android.annotation.SuppressLint
import android.net.Uri
import com.github.salomonbrys.kotson.array
import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.string
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*
import android.util.Base64

class MyMangaReaderCMSSource(override val lang: String,
                             override val name: String,
                             override val baseUrl: String,
                             override val supportsLatest: Boolean,
                             private val itemUrl: String,
                             private val categoryMappings: List<Pair<String, String>>,
                             private val tagMappings: List<Pair<String, String>>?) : HttpSource() {
    private val jsonParser = JsonParser()
    private val itemUrlPath = Uri.parse(itemUrl).pathSegments.firstOrNull()
    private val parsedBaseUrl = Uri.parse(baseUrl)

    override val client: OkHttpClient = network.cloudflareClient

    override fun popularMangaRequest(page: Int): Request {
        return when (name) {
            "Utsukushii" -> GET("$baseUrl/manga-list", headers)
            else -> GET("$baseUrl/filterList?page=$page&sortBy=views&asc=false", headers)
        }
    }
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        //Query overrides everything
        val url: Uri.Builder
        if (query.isNotBlank()) {
            url = Uri.parse("$baseUrl/search")!!.buildUpon()
            url.appendQueryParameter("query", query)
        } else {
            url = Uri.parse("$baseUrl/filterList?page=$page")!!.buildUpon()
            filters.filterIsInstance<UriFilter>()
                    .forEach { it.addToUri(url) }
        }
        return GET(url.toString(), headers)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/filterList?page=$page&sortBy=last_release&asc=false", headers)

    override fun popularMangaParse(response: Response) = internalMangaParse(response)
    override fun searchMangaParse(response: Response): MangasPage {
        return if (response.request().url().queryParameter("query")?.isNotBlank() == true) {
            //If a search query was specified, use search instead!
            MangasPage(jsonParser
                    .parse(response.body()!!.string())["suggestions"].array
                    .map {
                        SManga.create().apply {
                            val segment = it["data"].string
                            url = getUrlWithoutBaseUrl(itemUrl + segment)
                            title = it["value"].string

                            // Guess thumbnails
                            // thumbnail_url = "$baseUrl/uploads/manga/$segment/cover/cover_250x350.jpg"
                        }
                    }, false)
        } else {
            internalMangaParse(response)
        }
    }

    override fun latestUpdatesParse(response: Response) = internalMangaParse(response)

    private fun internalMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val internalMangaSelector = when (name) {
            "Utsukushii" -> "div.content div.col-sm-6"
            else -> "div[class^=col-sm], div.col-xs-6"
        }
        return MangasPage(document.select(internalMangaSelector).map {
            SManga.create().apply {
                val urlElement = it.getElementsByClass("chart-title")
                if (urlElement.size == 0) {
                    url = getUrlWithoutBaseUrl(it.select("a").attr("href"))
                    title = it.select("div.caption").text()
                    it.select("div.caption div").text().let { if (it.isNotEmpty()) title = title.substringBefore(it)} // To clean submanga's titles without breaking hentaishark's
                } else {
                    url = getUrlWithoutBaseUrl(urlElement.attr("href"))
                    title = urlElement.text().trim()
                }

                it.select("img").let { img ->
                    thumbnail_url = when {
                        it.hasAttr("data-background-image") -> it.attr("data-background-image") // Utsukushii
                        img.hasAttr("data-src") -> coverGuess(img.attr("abs:data-src"), url)
                        else -> coverGuess(img.attr("abs:src"), url)
                    }
                }
            }
        }, document.select(".pagination a[rel=next]").isNotEmpty())
    }

    // Guess thumbnails on broken websites
    private fun coverGuess(url: String, mangaUrl: String): String {
        return if (url.endsWith("no-image.png")) {
            "$baseUrl/uploads/manga/${mangaUrl.substringAfterLast('/')}/cover/cover_250x350.jpg"
        } else {
            url
        }
    }

    private fun getUrlWithoutBaseUrl(newUrl: String): String {
        val parsedNewUrl = Uri.parse(newUrl)
        val newPathSegments = parsedNewUrl.pathSegments.toMutableList()

        for (i in parsedBaseUrl.pathSegments) {
            if (i.trim().equals(newPathSegments.first(), true)) {
                newPathSegments.removeAt(0)
            } else break
        }

        val builtUrl = parsedNewUrl.buildUpon().path("/")
        newPathSegments.forEach { builtUrl.appendPath(it) }

        var out = builtUrl.build().encodedPath!!
        if (parsedNewUrl.encodedQuery != null)
            out += "?" + parsedNewUrl.encodedQuery
        if (parsedNewUrl.encodedFragment != null)
            out += "#" + parsedNewUrl.encodedFragment

        return out
    }

    @SuppressLint("DefaultLocale")
    override fun mangaDetailsParse(response: Response) = SManga.create().apply {
        val document = response.asJsoup()
        title = document.getElementsByClass("widget-title").text().trim()
        thumbnail_url = coverGuess(document.select(".row .img-responsive").attr("abs:src"), document.location())
        description = document.select(".row .well p").text().trim()

        val detailAuthor = setOf("author(s)","autor(es)","auteur(s)","著作","yazar(lar)","mangaka(lar)","pengarang/penulis","pengarang","penulis","autor","المؤلف","перевод")
        val detailArtist = setOf("artist(s)","artiste(s)","sanatçi(lar)","artista(s)","artist(s)/ilustrator","الرسام","seniman")
        val detailGenre = setOf("categories","categorías","catégories","ジャンル","kategoriler","categorias","kategorie","التصنيفات","жанр","kategori" )
        val detailStatus = setOf("status","statut","estado","状態","durum","الحالة","статус")
        val detailStatusComplete = setOf("complete","مكتملة","complet","completo")
        val detailStatusOngoing = setOf("ongoing","مستمرة","en cours","em lançamento")
        val detailDescription = setOf("description","resumen")

        var cur: String? = null
        for (element in document.select(".row .dl-horizontal").select("dt,dd")) {
            when (element.tagName()) {
                "dt" -> cur = element.text().trim().toLowerCase()
                "dd" -> when (cur) {
                    in detailAuthor -> author = element.text()
                    in detailArtist -> artist = element.text()
                    in detailGenre-> genre = element.getElementsByTag("a").joinToString {
                        it.text().trim()
                    }
                    in detailStatus -> status = when (element.text().trim().toLowerCase()) {
                        in detailStatusComplete -> SManga.COMPLETED
                        in detailStatusOngoing -> SManga.ONGOING
                        else -> SManga.UNKNOWN
                    }
                }
            }
        }
        // When details are in a .panel instead of .row
        for (element in document.select("div.panel span.list-group-item ")) {
            when (element.select("b").text().toLowerCase().substringBefore(":")) {
                in detailAuthor -> author = element.select("b + a").text()
                in detailArtist -> artist = element.select("b + a").text()
                in detailGenre -> genre = element.getElementsByTag("a").joinToString {
                    it.text().trim()
                }
                in detailStatus -> status = when (element.select("b + span.label").text().toLowerCase()) {
                    in detailStatusComplete -> SManga.COMPLETED
                    in detailStatusOngoing -> SManga.ONGOING
                    else -> SManga.UNKNOWN
                }
                in detailDescription -> description = element.ownText()
            }
        }
    }

    /**
     * Parses the response from the site and returns a list of chapters.
     *
     * Overriden to allow for null chapters
     *
     * @param response the response from the site.
     */
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select(chapterListSelector()).mapNotNull { nullableChapterFromElement(it) }
    }

    /**
     * Returns the Jsoup selector that returns a list of [Element] corresponding to each chapter.
     */
    private fun chapterListSelector() = "ul[class^=chapters] > li:not(.btn), table.table tr"
    //Some websites add characters after "chapters" thus the need of checking classes that starts with "chapters"

    /**
     * Returns a chapter from the given element.
     *
     * @param element an element obtained from [chapterListSelector].
     */
    private fun nullableChapterFromElement(element: Element): SChapter? {
        val chapter = SChapter.create()

        try {
            val titleWrapper = element.select("[class^=chapter-title-rtl]").first()
            //Some websites add characters after "..-rtl" thus the need of checking classes that starts with that
            val url = titleWrapper.getElementsByTag("a").attr("href")

            // Ensure chapter actually links to a manga
            // Some websites use the chapters box to link to post announcements
            // The check is skipped if mangas are stored in the root of the website (ex '/one-piece' without a segment like '/manga/one-piece')
            if (itemUrlPath != null && !Uri.parse(url).pathSegments.firstOrNull().equals(itemUrlPath, true)) {
                return null
            }

            chapter.url = getUrlWithoutBaseUrl(url)
            chapter.name = titleWrapper.text()

            // Parse date
            val dateText = element.getElementsByClass("date-chapter-title-rtl").text().trim()
            chapter.date_upload = parseDate(dateText)

            return chapter
        } catch (e: NullPointerException) {
            // For chapter list in a table
            if (element.select("td").hasText()) {
                element.select("td a").let {
                    chapter.setUrlWithoutDomain(it.attr("href"))
                    chapter.name = it.text()
                }
                val tableDateText = element.select("td + td").text()
                chapter.date_upload = parseDate(tableDateText)

                return chapter
            }
        }

        return null
    }

    private fun parseDate (dateText: String): Long {
        return try {
            DATE_FORMAT.parse(dateText).time
        } catch (e: ParseException) {
            0L
        }
    }

    override fun pageListParse(response: Response) = response.asJsoup().select("#all > .img-responsive")
            .mapIndexed { i, e ->
                var url = e.attr("abs:data-src")

                if (url.isBlank()) {
                    url = e.attr("abs:src")
                }

                url = url.trim()

                // Mangas.pw encodes some of their urls, decode them
                if (url.contains("mangas.pw") && url.contains("img.php")) {
                    url = url.substringAfter("i=")
                    repeat (5) {
                        url = Base64.decode(url, Base64.DEFAULT).toString(Charsets.UTF_8).substringBefore("=")
                    }
                }

                Page(i, url, url)
            }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException("Unused method called!")

    private fun getInitialFilterList() = listOf<Filter<*>>(
            Filter.Header("NOTE: Ignored if using text search!"),
            Filter.Separator(),
            AuthorFilter(),
            UriSelectFilter("Category",
                    "cat",
                    arrayOf("" to "Any",
                            *categoryMappings.toTypedArray()
                    )
            ),
            UriSelectFilter("Begins with",
                    "alpha",
                    arrayOf("" to "Any",
                            *"#ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray().map {
                                Pair(it.toString(), it.toString())
                            }.toTypedArray()
                    )
            ),
            SortFilter()
    )

    /**
     * Returns the list of filters for the source.
     */
    override fun getFilterList() = FilterList(
            if (tagMappings != null)
                (getInitialFilterList() + UriSelectFilter("Tag",
                        "tag",
                        arrayOf("" to "Any",
                                *tagMappings.toTypedArray()
                        )))
            else getInitialFilterList()
    )

    /**
     * Class that creates a select filter. Each entry in the dropdown has a name and a display name.
     * If an entry is selected it is appended as a query parameter onto the end of the URI.
     * If `firstIsUnspecified` is set to true, if the first entry is selected, nothing will be appended on the the URI.
     */
    //vals: <name, display>
    open class UriSelectFilter(displayName: String, private val uriParam: String, private val vals: Array<Pair<String, String>>,
                               private val firstIsUnspecified: Boolean = true,
                               defaultValue: Int = 0) :
            Filter.Select<String>(displayName, vals.map { it.second }.toTypedArray(), defaultValue), UriFilter {
        override fun addToUri(uri: Uri.Builder) {
            if (state != 0 || !firstIsUnspecified)
                uri.appendQueryParameter(uriParam, vals[state].first)
        }
    }

    class AuthorFilter : Filter.Text("Author"), UriFilter {
        override fun addToUri(uri: Uri.Builder) {
            uri.appendQueryParameter("author", state)
        }
    }

    class SortFilter : Filter.Sort("Sort",
            sortables.map { it.second }.toTypedArray(),
            Selection(0, true)), UriFilter {
        override fun addToUri(uri: Uri.Builder) {
            uri.appendQueryParameter("sortBy", sortables[state!!.index].first)
            uri.appendQueryParameter("asc", state!!.ascending.toString())
        }

        companion object {
            private val sortables = arrayOf(
                    "name" to "Name",
                    "views" to "Popularity",
                    "last_release" to "Last update"
            )
        }
    }

    /**
     * Represents a filter that is able to modify a URI.
     */
    interface UriFilter {
        fun addToUri(uri: Uri.Builder)
    }

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("d MMM. yyyy", Locale.US)
    }
}