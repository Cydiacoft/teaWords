package com.tea.teawords.data

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

class WordLevelProvider(private val context: Context) {

    private val cet4Set = HashSet<String>()
    private val cet6Set = HashSet<String>()
    private val tem4Set = HashSet<String>()
    private val tem8Set = HashSet<String>()
    private val kySet = HashSet<String>()
    private var isLoaded = false

    fun loadIfNeeded() {
        if (isLoaded) return
        synchronized(this) {
            if (isLoaded) return
            try {
                loadList("cet4.txt", cet4Set)
                loadList("cet6.txt", cet6Set)
                loadOptionalList("tem4.txt", tem4Set)
                loadOptionalList("tem8.txt", tem8Set)
                loadOptionalList("ky.txt", kySet)
                if (tem4Set.isEmpty()) tem4Set.addAll(defaultTem4Words)
                if (tem8Set.isEmpty()) tem8Set.addAll(defaultTem8Words)
                if (kySet.isEmpty()) kySet.addAll(defaultKyWords)
                isLoaded = true
            } catch (e: Exception) {
                Log.e("WordLevelProvider", "Error loading word lists: ${e.message}", e)
            }
        }
    }

    private fun loadList(filename: String, targetSet: HashSet<String>) {
        context.assets.open(filename).use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, "UTF-8")).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line!!.trim()
                    if (trimmed.isEmpty()) continue
                    
                    // Skip single-character capital letter headers (like "A", "B", etc.)
                    if (trimmed.length == 1 && trimmed[0].isUpperCase()) continue
                    
                    // Split on whitespace or opening bracket to get the word
                    val parts = trimmed.split(Regex("[\\s\\[]"), 2)
                    val word = parts.firstOrNull()?.trim()?.lowercase() ?: ""
                    
                    // Basic sanity check to ensure it's a valid English word
                    if (word.isNotEmpty() && word.all { it.isLetter() || it == '-' || it == '\'' }) {
                        targetSet.add(word)
                    }
                }
            }
        }
        Log.d("WordLevelProvider", "Loaded ${targetSet.size} words from $filename")
    }

    private fun loadOptionalList(filename: String, targetSet: HashSet<String>) {
        try {
            loadList(filename, targetSet)
        } catch (e: Exception) {
            Log.w("WordLevelProvider", "Optional word list missing: $filename")
        }
    }

    fun getLevels(word: String): List<String> {
        loadIfNeeded()
        val cleanedWord = word.trim().lowercase()
        val levels = mutableListOf<String>()
        if (cet4Set.contains(cleanedWord)) {
            levels.add("四级 CET-4")
        }
        if (cet6Set.contains(cleanedWord)) {
            levels.add("六级 CET-6")
        }
        if (tem4Set.contains(cleanedWord)) {
            levels.add("专四 TEM-4")
        }
        if (tem8Set.contains(cleanedWord)) {
            levels.add("专八 TEM-8")
        }
        if (kySet.contains(cleanedWord)) {
            levels.add("考研英语")
        }
        return levels
    }

    fun searchPrefix(prefix: String, limit: Int = 8): List<String> {
        loadIfNeeded()
        val cleanedPrefix = prefix.trim().lowercase()
        if (cleanedPrefix.isEmpty()) return emptyList()

        return sequenceOf(cet4Set, cet6Set, tem4Set, tem8Set, kySet)
            .flatMap { it.asSequence() }
            .filter { it.startsWith(cleanedPrefix) }
            .distinct()
            .sorted()
            .take(limit)
            .toList()
    }

    fun getWordbookItems(bookIds: Set<String>, limitPerBook: Int = 80): List<VocabularyItem> {
        loadIfNeeded()
        val items = mutableListOf<VocabularyItem>()
        bookIds.forEach { id ->
            when (id) {
                LearningWordbook.CET4.id -> items.addAll(loadVocabularyItems("cet4.txt", limitPerBook))
                LearningWordbook.CET6.id -> items.addAll(loadVocabularyItems("cet6.txt", limitPerBook))
                LearningWordbook.TEM4.id -> items.addAll(defaultTem4Definitions.toVocabularyItems())
                LearningWordbook.TEM8.id -> items.addAll(defaultTem8Definitions.toVocabularyItems())
                LearningWordbook.KY.id -> items.addAll(loadVocabularyItems("ky.txt", limitPerBook))
            }
        }

        return items
            .distinctBy { it.word.lowercase() }
            .shuffled()
    }

    private fun loadVocabularyItems(filename: String, limit: Int): List<VocabularyItem> {
        val items = mutableListOf<VocabularyItem>()
        try {
            context.assets.open(filename).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, "UTF-8")).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null && items.size < limit) {
                        parseVocabularyLine(line.orEmpty())?.let { items.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("WordLevelProvider", "Unable to load wordbook items from $filename: ${e.message}")
        }
        return items
    }

    private fun parseVocabularyLine(line: String): VocabularyItem? {
        val trimmed = line.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty()) return null
        if (trimmed.length == 1 && trimmed[0].isUpperCase()) return null
        if (!trimmed.first().isLetter()) return null

        val word = trimmed.takeWhile { !it.isWhitespace() && it != '[' }.lowercase()
        if (word.isEmpty() || !word.all { it.isLetter() || it == '-' || it == '\'' }) return null

        val phonetic = Regex("\\[[^]]+]").find(trimmed)?.value
        val definition = trimmed
            .substringAfter(phonetic ?: word, "")
            .replace(Regex("^\\s+"), "")
            .ifBlank { "释义待补充" }

        return VocabularyItem(
            word = word,
            phonetic = phonetic,
            definition = definition,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun Map<String, String>.toVocabularyItems(): List<VocabularyItem> {
        return map { (word, definition) ->
            VocabularyItem(
                word = word,
                phonetic = null,
                definition = definition,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    fun getWordbookCount(id: String): Int {
        loadIfNeeded()
        return when (id) {
            LearningWordbook.CET4.id -> cet4Set.size
            LearningWordbook.CET6.id -> cet6Set.size
            LearningWordbook.TEM4.id -> tem4Set.size
            LearningWordbook.TEM8.id -> tem8Set.size
            LearningWordbook.KY.id -> kySet.size
            else -> 0
        }
    }

    companion object {
        private val defaultTem4Words = setOf(
            "abide", "abolish", "abound", "abrupt", "absurd", "acclaim", "accommodate",
            "accumulate", "adhere", "adjacent", "adolescent", "adverse", "advocate",
            "ambiguous", "analogy", "apparatus", "arbitrary", "authentic", "coherent",
            "coincide", "collaborate", "compulsory", "consecutive", "contemplate",
            "controversial", "dedicate", "deficiency", "deliberate", "diplomatic",
            "elaborate", "eligible", "equivalent", "explicit", "fluctuate", "incentive",
            "inevitable", "integrity", "legislation", "manipulate", "notorious",
            "obstacle", "preliminary", "profound", "prosperity", "radical", "restore",
            "subordinate", "substantial", "terminate", "unanimous"
        )

        private val defaultTem8Words = setOf(
            "aberration", "abstinence", "acquiesce", "alleviate", "ambivalent",
            "anachronism", "antagonism", "apprehensive", "belligerent", "benevolent",
            "capricious", "circumspect", "connoisseur", "conspicuous", "cumulative",
            "debilitate", "detrimental", "discernible", "eclectic", "emulate",
            "ephemeral", "equivocal", "exacerbate", "fastidious", "formidable",
            "gregarious", "idiosyncrasy", "impeccable", "indigenous", "inquisitive",
            "meticulous", "oblivious", "paradoxical", "peripheral", "pervasive",
            "pragmatic", "proliferate", "reconcile", "resilient", "scrutinize",
            "spontaneous", "subtle", "tantamount", "tenacious", "ubiquitous"
        )

        private val defaultTem4Definitions = mapOf(
            "abide" to "v. 遵守；忍受",
            "abolish" to "v. 废除，取消",
            "abound" to "v. 大量存在，充满",
            "abrupt" to "a. 突然的；唐突的",
            "absurd" to "a. 荒谬的",
            "acclaim" to "v./n. 称赞，赞扬",
            "accommodate" to "v. 容纳；使适应",
            "accumulate" to "v. 积累，聚积",
            "adhere" to "v. 坚持；粘附",
            "adjacent" to "a. 邻近的",
            "adverse" to "a. 不利的，有害的",
            "advocate" to "v./n. 提倡；拥护者",
            "ambiguous" to "a. 模棱两可的",
            "analogy" to "n. 类比",
            "coherent" to "a. 连贯的，一致的",
            "collaborate" to "v. 合作，协作",
            "compulsory" to "a. 强制的，必修的",
            "controversial" to "a. 有争议的",
            "diplomatic" to "a. 外交的；圆滑的",
            "eligible" to "a. 有资格的，合格的",
            "explicit" to "a. 明确的，清楚的",
            "incentive" to "n. 激励，动机",
            "integrity" to "n. 正直；完整",
            "legislation" to "n. 立法；法规",
            "manipulate" to "v. 操纵，操作",
            "preliminary" to "a. 初步的，预备的",
            "profound" to "a. 深刻的；渊博的",
            "substantial" to "a. 大量的；实质的",
            "terminate" to "v. 终止，结束",
            "unanimous" to "a. 一致同意的"
        )

        private val defaultKyWords = setOf(
            "abandon", "absorb", "abstract", "abundant", "accelerate", "access", "accompany",
            "accomplish", "account", "accumulate", "accurate", "accuse", "achieve", "acknowledge",
            "acquire", "adapt", "adequate", "adjust", "administration", "adopt", "advance",
            "advantage", "advertise", "affair", "affect", "afford", "aggressive", "agriculture",
            "allocate", "alternative", "amaze", "ambition", "analyze", "announce", "annual",
            "anxiety", "apparent", "appeal", "appetite", "appliance", "application", "appoint",
            "appreciate", "approach", "appropriate", "approve", "arise", "arrange", "artificial",
            "aspect", "assemble", "assess", "assign", "assist", "associate", "assume", "assure",
            "atmosphere", "attach", "attempt", "attend", "attitude", "attract", "attribute",
            "authority", "automatic", "available", "aware", "balance", "barrier", "behalf",
            "behave", "benefit", "betray", "bewilder", "boost", "boundary", "brand", "breed",
            "budget", "burden", "calculate", "campaign", "capable", "capacity", "capture",
            "career", "category", "celebrate", "challenge", "character", "characteristic",
            "circumstance", "cite", "civilization", "claim", "clarify", "classic", "climate",
            "collapse", "combine", "command", "comment", "commerce", "commit", "communicate",
            "community", "companion", "compare", "compel", "compensate", "compete", "complain",
            "complex", "complicate", "component", "compose", "comprehend", "comprehensive",
            "concentrate", "concept", "concern", "conclude", "concrete", "condition", "conduct",
            "conference", "confident", "confine", "confirm", "conflict", "confront", "congress",
            "conscience", "conscious", "consequence", "consequently", "conservation", "conservative",
            "considerable", "consistent", "constant", "constitute", "construct", "consult",
            "consume", "contact", "contain", "contemporary", "content", "contest", "context",
            "contract", "contrary", "contribute", "controversy", "convenient", "convention",
            "convince", "cooperate", "coordinate", "cope", "correspond", "council", "counsel",
            "create", "creature", "crisis", "criteria", "critical", "cultivate", "culture",
            "curiosity", "current", "database", "debate", "debt", "decade", "deceive", "decent",
            "declare", "decline", "decorate", "decrease", "defeat", "defend", "define",
            "definitely", "definition", "deliver", "demand", "demonstrate", "deny", "depart",
            "depend", "depict", "deposit", "depress", "derive", "deserve", "design", "desire",
            "desperate", "despite", "destination", "destroy", "detect", "determine", "develop",
            "device", "devote", "differ", "digital", "dignity", "dilemma", "dimension",
            "diminish", "discipline", "disclose", "discount", "discourage", "display", "dispose",
            "distinct", "distinguish", "distribute", "disturb", "diverse", "document", "domestic",
            "dominate", "dramatic", "duration", "dynamic", "economical", "edition", "efficient",
            "elaborate", "eliminate", "embrace", "emerge", "emission", "emotion", "emphasis",
            "employ", "enable", "encounter", "encourage", "enormous", "ensure", "enterprise",
            "entertainment", "enthusiasm", "entire", "entitle", "environment", "episode",
            "equation", "equipment", "equivalent", "establish", "estate", "estimate", "evaluate",
            "evident", "evolution", "exceed", "excellent", "exception", "excess", "exchange",
            "exclude", "execute", "exercise", "exhaust", "exhibit", "expand", "expense",
            "experiment", "expert", "exploit", "explore", "export", "expose", "extend",
            "extensive", "extent", "external", "extraordinary", "extreme", "facility", "factor",
            "faculty", "failure", "fashion", "feasible", "feature", "federal", "feedback",
            "fiction", "finance", "flexible", "forecast", "formula", "fortune", "foundation",
            "fragment", "framework", "frequency", "fulfill", "function", "fundamental", "furthermore",
            "generate", "generous", "genius", "genuine", "gesture", "global", "glory", "govern",
            "grace", "gradual", "grant", "guarantee", "guidance", "harmony", "hesitate", "highlight",
            "horizon", "hostile", "household", "identify", "ignorance", "illustrate", "imaginary",
            "immediate", "immense", "immigrant", "impact", "implement", "imply", "impose",
            "impress", "impulse", "incident", "incline", "incredible", "independent", "indicate",
            "individual", "inevitable", "infant", "influence", "inform", "ingredient", "inhabit",
            "inherit", "initial", "initiative", "inner", "innovation", "inspect", "inspire",
            "install", "instance", "institute", "instrument", "insult", "insurance", "intellectual",
            "intelligence", "intense", "intention", "interact", "interfere", "interior",
            "internal", "interpret", "interrupt", "interval", "interview", "intimate", "invade",
            "invent", "invest", "investigate", "involve", "isolate", "issue", "joint", "journal",
            "judgment", "justify", "laboratory", "launch", "layer", "layout", "league", "legal",
            "legend", "legislation", "liberal", "liberty", "likewise", "limit", "literacy",
            "literary", "literature", "living", "location", "logic", "longitude", "maintain",
            "majority", "management", "manufacture", "margin", "massive", "mature", "maximum",
            "mechanism", "medium", "mental", "mention", "merchant", "mercy", "mild", "military",
            "minimum", "ministry", "minority", "miracle", "miserable", "mission", "moderate",
            "modest", "modify", "monitor", "mood", "moral", "moreover", "motivate", "multiple",
            "mutual", "mysterious", "narrow", "nationality", "negative", "neglect", "negotiate",
            "neighbor", "neutral", "nevertheless", "normal", "notion", "nuclear", "numerous",
            "objection", "objective", "obligation", "observe", "obstacle", "obtain", "obvious",
            "occasion", "occupy", "offense", "official", "operate", "opinion", "opponent",
            "opportunity", "oppose", "optimistic", "option", "orbit", "organize", "origin",
            "original", "outcome", "outline", "output", "overcome", "overlook", "overseas",
            "panel", "paradise", "paragraph", "parallel", "participate", "particular", "partner",
            "passion", "passive", "patience", "payment", "penalty", "perceive", "percent",
            "perfect", "perform", "period", "permanent", "permit", "persist", "personality",
            "perspective", "persuade", "phenomenon", "philosophy", "phrase", "physical", "pilot",
            "planet", "platform", "pleasure", "pledge", "plot", "policy", "pollution", "populate",
            "portion", "portrait", "position", "positive", "possess", "potential", "poverty",
            "practical", "pray", "precious", "precise", "predict", "prefer", "prejudice",
            "preparation", "prescribe", "presence", "preserve", "pressure", "presumably",
            "previous", "pride", "primary", "principle", "priority", "private", "privilege",
            "probable", "procedure", "proceed", "process", "professional", "profit", "progress",
            "prohibit", "project", "prominent", "promote", "prompt", "proof", "property",
            "proportion", "proposal", "propose", "prospect", "prosperity", "protect", "protest",
            "prove", "provide", "provoke", "psychology", "publication", "publish", "purchase",
            "pursue", "qualify", "quantity", "quarter", "radical", "raise", "random", "range",
            "rapid", "rare", "rate", "rational", "react", "readily", "realistic", "reasonable",
            "recall", "receive", "recent", "recognition", "recommend", "recover", "recreation",
            "reduce", "refer", "reference", "reflect", "reform", "refuge", "refuse", "regard",
            "region", "register", "regulate", "reinforce", "reject", "relate", "relative",
            "release", "relevant", "relief", "rely", "remain", "remark", "remedy", "remote",
            "remove", "render", "replace", "represent", "republic", "reputation", "request",
            "require", "research", "resemble", "reserve", "residence", "resign", "resist",
            "resolution", "resolve", "resource", "respond", "responsibility", "restore",
            "restrict", "result", "retain", "retire", "retreat", "reveal", "revenue", "reverse",
            "revise", "revolution", "reward", "rhythm", "ridiculous", "rigid", "rival",
            "romantic", "routine", "sacrifice", "safety", "salary", "sample", "satellite",
            "satisfaction", "scale", "scatter", "schedule", "scheme", "scholar", "scratch",
            "screen", "seal", "section", "secure", "seek", "select", "senior", "sense",
            "sensitive", "separate", "sequence", "series", "session", "settle", "severe",
            "shadow", "shrink", "significance", "significant", "similar", "simulate", "sincere",
            "single", "sketch", "slave", "smooth", "social", "software", "solar", "sole",
            "solid", "solution", "somehow", "sophisticated", "source", "span", "spare",
            "specialist", "species", "specific", "specify", "spectacular", "sphere", "spirit",
            "sponsor", "spot", "spread", "squeeze", "stable", "standard", "startle", "statistics",
            "status", "steady", "stimulate", "strategic", "strategy", "strength", "stress",
            "structure", "struggle", "studio", "substance", "substitute", "subtract", "succession",
            "sufficient", "suggestion", "summit", "superior", "supplement", "supply", "support",
            "suppose", "supreme", "surface", "surgery", "surplus", "surrender", "surround",
            "survey", "survive", "suspect", "suspend", "sustain", "symbol", "sympathy",
            "symptom", "system", "tackle", "technique", "technology", "temporary", "tendency",
            "tender", "terminal", "territory", "terror", "theory", "therapy", "thereby",
            "thorough", "threat", "thrill", "thrive", "thumb", "tolerance", "topic", "torture",
            "tough", "track", "tradition", "trait", "transfer", "transform", "transit",
            "transmit", "transparent", "transport", "trap", "treaty", "tremble", "tremendous",
            "trend", "trial", "tribe", "trigger", "triumph", "tropical", "troublesome", "truly",
            "trust", "truth", "tutor", "typical", "ultimate", "uncover", "undergo", "undergraduate",
            "undertake", "unique", "universal", "urban", "urge", "urgent", "utility", "utilize",
            "utmost", "utter", "vacant", "vague", "valid", "vanish", "variable", "vast",
            "vehicle", "venture", "verbal", "verify", "version", "vertical", "veteran",
            "victim", "vigorous", "violate", "virtual", "virtue", "visible", "vision",
            "vital", "vivid", "volume", "voluntary", "voyage", "wander", "wealth", "weapon",
            "welfare", "widespread", "witness", "workforce", "worship", "worthwhile", "yield",
            "zone"
        )


        private val defaultTem8Definitions = mapOf(
            "aberration" to "n. 偏差，异常",
            "acquiesce" to "v. 默许，勉强同意",
            "alleviate" to "v. 减轻，缓和",
            "ambivalent" to "a. 矛盾的，摇摆不定的",
            "anachronism" to "n. 时代错误",
            "antagonism" to "n. 对抗，敌意",
            "apprehensive" to "a. 忧虑的；有理解力的",
            "belligerent" to "a. 好战的",
            "benevolent" to "a. 仁慈的，善意的",
            "capricious" to "a. 反复无常的",
            "circumspect" to "a. 谨慎周到的",
            "connoisseur" to "n. 鉴赏家，行家",
            "conspicuous" to "a. 显眼的，明显的",
            "debilitate" to "v. 使衰弱",
            "detrimental" to "a. 有害的",
            "eclectic" to "a. 折中的；兼收并蓄的",
            "ephemeral" to "a. 短暂的",
            "equivocal" to "a. 含糊的，可疑的",
            "exacerbate" to "v. 使恶化，加剧",
            "fastidious" to "a. 挑剔的，讲究的",
            "formidable" to "a. 强大的；令人敬畏的",
            "idiosyncrasy" to "n. 特质，习性",
            "impeccable" to "a. 无瑕疵的",
            "meticulous" to "a. 一丝不苟的",
            "oblivious" to "a. 未察觉的，健忘的",
            "paradoxical" to "a. 似矛盾而正确的",
            "pervasive" to "a. 到处弥漫的",
            "pragmatic" to "a. 务实的",
            "proliferate" to "v. 激增，扩散",
            "ubiquitous" to "a. 无处不在的"
        )
    }
}
