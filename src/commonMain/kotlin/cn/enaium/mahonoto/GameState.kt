package cn.enaium.mahonoto

import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLKeycode
import cn.enaium.sdl.SDLRect

/**
 * The game screen state machine. Ported from the original Flash game logic
 * (scripts/frame_1158 DoAction.as + DefineSprite_718 loop).
 */
class GameState(private val assets: Assets, private val text: TextRenderer, private val audio: Audio) {

    // ---------- screen states ----------
    enum class Screen { TITLE, HELP, GAME, GAME_OVER }

    var screen = Screen.TITLE
    var helpPage = 0
    var titleChoice = 0
    private var gameOverTicks = 0

    // ---------- maps ----------
    val lines = Array(22) { Array(11) { IntArray(11) } }
    val boss = HashMap<Int, BossData>()

    // ---------- stats ----------
    var nowLife = 1
    var nowHp = 1000
    var nowGong = 10
    var nowFang = 10
    var nowMoney = 0
    var nowMp = 0
    var nowYellow = 0
    var nowBlue = 0
    var nowRed = 0

    // ---------- flags ----------
    var nowKeyFlag = 1
    var nowLine = 0
    var nowXid = 0
    var nowYid = 0
    var maxLine = 0
    var listFlag = 0
    var jumpFlag = 0
    var cleanFlag = 0
    var myLove = 0
    var bigClean = 0
    var setMan01 = 0
    var setMan02 = 0
    var displayKills = 0
    var lastX = 0
    var lastY = 0
    var manX = 0
    var manY = 0

    // ---------- UI state ----------
    var displayText = 0        // 0 none, 1 showing, 2 done
    var displayKill = 0
    var displayBuy = 0
    var displayOther = 0
    var displayList = 0
    var displayLines = 0
    var displayJump = 0
    var openRoom = 0
    var displaySay = IntArray(17)

    var textMessage = ""
    var textTicks = 0

    var killLeftHp = 0
    var killRightHp = 0
    var killLeftGong = 0
    var killRightGong = 0
    var killLeftFang = 0
    var killRightFang = 0
    var killNumber = 0
    var killTicks = 0
    private var critRoll = 1
    var nowBossId = 0
    var nowBossX = 0
    var nowBossY = 0

    var doorTicks = 0
    var doorType = 0
    var lineFade = 0f // 0 = hidden, 1 = fully shown
    var fadeDir = 0 // -1 fade out, +1 fade in

    var playerFrame = 0
    var playerDir = 0 // 0 down, 1 left, 2 right, 3 up
    var playerTicks = 0

    var sayKey = -1
    var sayStage = 0
    var sayDialog = -1 // which say dialog (24/25/26/27/28/119/129) is active

    var buyFrame = 1
    var buyCase = 1

    var jumpSelection = 1

    var otherFrame = 1

    var monsterList = ArrayList<Int>()
    var listTicks = 0

    var lastKeyDown = 0L

    // ---------- setup ----------
    fun init() {
        // parse maps
        val blocks = MAP_DATA.trim().split("\n\n")
        for (l in 0 until 22) {
            val rows = blocks[l].trim().split("\n")
            for (r in 0 until 11) {
                val cells = rows[r].split(",")
                for (c in 0 until 11) {
                    lines[l][r][c] = cells[c].trim().toInt()
                }
            }
        }
        boss.clear()
        boss.putAll(BOSS_DATA)
    }

    fun reset() {
        nowLife = 1
        nowHp = 1000
        nowGong = 10
        nowFang = 10
        nowMoney = 0
        nowMp = 0
        nowYellow = 0
        nowBlue = 0
        nowRed = 0
        nowKeyFlag = 1
        nowLine = 0
        maxLine = 0
        listFlag = 0
        jumpFlag = 0
        cleanFlag = 0
        myLove = 0
        bigClean = 0
        setMan01 = 0
        setMan02 = 0
        displayKills = 0
        displayText = 0
        displayKill = 0
        displayBuy = 0
        displayOther = 0
        displayList = 0
        displayLines = 0
        displayJump = 0
        openRoom = 0
        displaySay = IntArray(17)
        textMessage = ""
        textTicks = 0
        fadeDir = 0
        lineFade = 0f
        doorTicks = 0
        sayKey = -1
        sayStage = 0
        sayDialog = -1
        buyFrame = 1
        buyCase = 1
        jumpSelection = 1
        otherFrame = 1
        monsterList = ArrayList()
        // restore base boss stats (in case of restarts)
        BOSS_DATA.forEach { (k, v) -> boss[k] = v.copy() }
        // re-parse fresh maps
        val blocks = MAP_DATA.trim().split("\n\n")
        for (l in 0 until 22) {
            val rows = blocks[l].trim().split("\n")
            for (r in 0 until 11) {
                val cells = rows[r].split(",")
                for (c in 0 until 11) {
                    lines[l][r][c] = cells[c].trim().toInt()
                }
            }
        }
        audio.stopAll()
    }

    fun tile(l: Int = nowLine, x: Int, y: Int): Int = lines[l][x][y]

    fun setTile(l: Int, x: Int, y: Int, v: Int) {
        lines[l][x][y] = v
    }

    // =====================================================================
    //  Input
    // =====================================================================

    fun onKeyDown(keycode: Int) {
        when (screen) {
            Screen.TITLE -> onTitleKey(keycode)
            Screen.HELP -> onHelpKey(keycode)
            Screen.GAME -> onGameKey(keycode)
            Screen.GAME_OVER -> if (keycode == SDLKeycode.ESCAPE || keycode == SDLKeycode.SPACE || keycode == SDLKeycode.RETURN) {
                screen = Screen.TITLE
                audio.stopAll()
            }
        }
    }

    private fun onTitleKey(keycode: Int) {
        when (keycode) {
            SDLKeycode.UP, 0x77 -> {
                titleChoice = (titleChoice + 2) % 3
                audio.playSfx("10")
            }
            SDLKeycode.DOWN, 0x73 -> {
                titleChoice = (titleChoice + 1) % 3
                audio.playSfx("10")
            }
            SDLKeycode.SPACE, SDLKeycode.RETURN -> titleActivate()
            SDLKeycode.ESCAPE -> {}
        }
    }

    fun titleActivate() {
        when (titleChoice) {
            0 -> startGame()
            1 -> {
                screen = Screen.HELP
                helpPage = 0
                audio.playSfx("14")
            }
            2 -> screen = Screen.GAME_OVER
        }
    }

    private fun onHelpKey(keycode: Int) {
        if (keycode == SDLKeycode.SPACE || keycode == SDLKeycode.RETURN || keycode == SDLKeycode.RIGHT) {
            // the four flag pages set their feature flags, then the pages
            // advance; after the last page we return to the title.
            when (helpPage) {
                0 -> listFlag = 1
                1 -> jumpFlag = 1
                2 -> cleanFlag = 1
                3 -> myLove = 1
            }
            helpPage++
            audio.playSfx("14")
            if (helpPage >= 4) {
                screen = Screen.TITLE
                audio.stopAll()
            }
        } else if (keycode == SDLKeycode.ESCAPE) {
            screen = Screen.TITLE
            audio.stopAll()
        }
    }

    fun startGame() {
        reset()
        screen = Screen.GAME
        nowLine = 0
        setLineDisplay(0)
        playBgmForLine()
    }

    private fun onGameKey(keycode: Int) {
        // global keys
        if (keycode == SDLKeycode.ESCAPE) {
            // toggle back to title
            audio.stopAll()
            screen = Screen.TITLE
            return
        }
        if (nowKeyFlag == 1) {
            when (keycode) {
                SDLKeycode.LEFT -> moveArrow(1)
                SDLKeycode.RIGHT -> moveArrow(2)
                SDLKeycode.UP -> moveArrow(3)
                SDLKeycode.DOWN -> moveArrow(4)
                SDLKeycode.R -> {
                    audio.playSfx("14")
                    reset()
                    nowLine = 0
                    setLineDisplay(0)
                    playBgmForLine()
                }
                SDLKeycode.Q -> {
                    audio.playSfx("14")
                    nowLine = 0
                    setLineDisplay(0)
                    playBgmForLine()
                }
                SDLKeycode.L -> {
                    if (listFlag == 1 && displayList == 0) {
                        nowKeyFlag = 0
                        displayList = 1
                        audio.playSfx("04")
                    } else if (displayList == 2) {
                        displayList = 3
                        nowKeyFlag = 1
                        audio.playSfx("14")
                    }
                }
                SDLKeycode.J -> {
                    if (jumpFlag == 1 && displayJump == 0) {
                        nowKeyFlag = 0
                        displayJump = 3
                        jumpSelection = 1
                        audio.playSfx("04")
                    } else if (displayJump == 3) {
                        displayJump = 4
                        nowKeyFlag = 1
                    }
                }
                0x32 -> { // '2': move selection down
                    if (displayBuy == 1 && buyFrame != 1) buyMove(1)
                    else if (displayJump == 3) jumpMove(1)
                }
                0x38 -> { // '8': move selection up
                    if (displayBuy == 1 && buyFrame != 1) buyMove(-1)
                    else if (displayJump == 3) jumpMove(-1)
                }
                0x35 -> { // '5': confirm
                    if (displayBuy == 1 && buyFrame != 1) buyAction(confirm = true)
                    else if (displayJump == 3) {
                        if (jumpSelection < maxLine) {
                            audio.playSfx("07")
                            nowLine = jumpSelection
                            displayJump = 2
                        } else {
                            audio.playSfx("10")
                        }
                    }
                }
                SDLKeycode.SPACE -> {
                    if (displayJump == 3) {
                        // confirm jump
                        if (jumpSelection < maxLine) {
                            audio.playSfx("07")
                            nowLine = jumpSelection
                            displayJump = 2
                        } else {
                            audio.playSfx("10")
                        }
                    } else if (displayBuy == 1) {
                        if (buyFrame == 1) {
                            buyFrame = 2
                            buyCase = 1
                            audio.playSfx("14")
                        } else {
                            buyAction(confirm = true)
                        }
                    } else if (displayOther == 1) {
                        closeOther()
                    } else if (displayList == 2) {
                        displayList = 3
                        nowKeyFlag = 1
                        audio.playSfx("14")
                    } else if (sayDialog in 0..16) {
                        advanceSay()
                    }
                }
            }
        } else {
            // not key-flag: still allow closing some panels
            when (keycode) {
                SDLKeycode.SPACE -> {
                    if (displayJump == 3) {
                        if (jumpSelection < maxLine) {
                            audio.playSfx("07")
                            nowLine = jumpSelection
                            displayJump = 2
                        } else {
                            audio.playSfx("10")
                        }
                    } else if (displayBuy == 1 && buyFrame != 1) {
                        buyAction(confirm = true)
                    } else if (sayDialog in 0..16) {
                        advanceSay()
                    }
                }
                SDLKeycode.J -> if (displayJump == 3) {
                    displayJump = 4
                    nowKeyFlag = 1
                }
            }
        }
    }

    private fun sayKeyActive(): Int = if (sayDialog >= 0) sayDialog else -1

    private fun activeSayIdx(): Int = if (sayDialog >= 0) sayDialog else 0

    // =====================================================================
    //  Movement & tile interaction (ported from Move_arrow)
    // =====================================================================

    fun moveArrow(arrowId: Int) {
        var tx = nowXid
        var ty = nowYid
        var soundId = 0
        when (arrowId) {
            1 -> if (ty > 0) {
                ty--
                soundId = 1
            }
            2 -> if (ty < 10) {
                ty++
                soundId = 1
            }
            3 -> if (tx > 0) {
                tx--
                soundId = 1
            }
            4 -> if (tx < 10) {
                tx++
                soundId = 1
            }
            else -> {}
        }
        val t = tile(nowLine, tx, ty)
        if (t == 0 || t == 97 || t == 98 || t == 99) {
            val old = tile(nowLine, nowXid, nowYid)
            if (old == 0 || old == 99) setTile(nowLine, nowXid, nowYid, 0)
            nowXid = tx
            nowYid = ty
            playerDir = when (arrowId) {
                1 -> 1
                2 -> 2
                3 -> 3
                else -> 0
            }
            playerFrame = 0
            if (soundId == 1) audio.playSfx("00")
        }

        when (t) {
            6 -> { // yellow key
                nowKeyFlag = 0
                showText("得到一个 黄钥匙 ！")
                audio.playSfx("01")
                setTile(nowLine, tx, ty, 0)
                nowYellow++
            }
            2 -> if (nowYellow > 0) { // yellow door
                nowKeyFlag = 0
                openRoom = 1
                doorTicks = 0
                doorType = 2
                audio.playSfx("07")
                setTile(nowLine, tx, ty, 0)
                lastX = tx
                lastY = ty
                nowYellow--
            }
            7 -> { // blue key
                nowKeyFlag = 0
                showText("得到一个 蓝钥匙 ！")
                audio.playSfx("01")
                setTile(nowLine, tx, ty, 0)
                nowBlue++
            }
            3 -> if (nowBlue > 0) { // blue door
                nowKeyFlag = 0
                openRoom = 1
                doorTicks = 0
                doorType = 3
                audio.playSfx("07")
                setTile(nowLine, tx, ty, 0)
                lastX = tx
                lastY = ty
                nowBlue--
            }
            8 -> { // red key
                nowKeyFlag = 0
                showText("得到一个 红钥匙 ！")
                audio.playSfx("01")
                setTile(nowLine, tx, ty, 0)
                nowRed++
            }
            4 -> if (nowRed > 0) { // red door
                nowKeyFlag = 0
                openRoom = 1
                doorTicks = 0
                doorType = 4
                audio.playSfx("07")
                setTile(nowLine, tx, ty, 0)
                lastX = tx
                lastY = ty
                nowRed--
            }
            in 40..70 -> fightMonster(t, tx, ty)
            11 -> { // small potion
                nowKeyFlag = 0
                showText("得到一个小血瓶 生命加 200 ！")
                audio.playSfx("01")
                setTile(nowLine, tx, ty, 0)
                nowHp += 200
            }
            12 -> { // big potion
                nowKeyFlag = 0
                showText("得到一个大血瓶 生命加 500 ！")
                audio.playSfx("01")
                setTile(nowLine, tx, ty, 0)
                nowHp += 500
            }
            10 -> { // red gem
                nowKeyFlag = 0
                showText("得到一个红宝石 攻击力加 3 ！")
                audio.playSfx("01")
                setTile(nowLine, tx, ty, 0)
                nowGong += 3
            }
            9 -> { // blue gem
                nowKeyFlag = 0
                showText("得到一个蓝宝石 防御力加 3 ！")
                audio.playSfx("01")
                setTile(nowLine, tx, ty, 0)
                nowFang += 3
            }
            13, 14 -> { // stairs
                nowKeyFlag = 0
                audio.playSfx("07")
                displayLines = 2
                fadeDir = 1
                lineFade = 0f
                if (t == 13) myLifesUp = 1 else myLifesDown = 1
            }
            24 -> if (displaySay[0] == 0) {
                nowKeyFlag = 0
                displaySay[0] = 1
                sayDialog = 0
                sayKey = 0
                sayStage = 0
                audio.playSfx("04")
                if (myLove == 1) {
                    myLove = 0
                    sayStage = 1 // say_two: skip first part
                }
            } else if (displaySay[0] == 1) {
                // already talking
            }
            28 -> if (displaySay[6] == 0) {
                nowKeyFlag = 0
                displaySay[6] = 1
                sayDialog = 6
                sayStage = 0
                audio.playSfx("04")
            }
            119 -> if (displaySay[7] == 0) {
                nowKeyFlag = 0
                displaySay[7] = 1
                sayDialog = 7
                sayStage = 0
                audio.playSfx("04")
            }
            129 -> if (displaySay[8] == 0) {
                nowKeyFlag = 0
                displaySay[8] = 1
                sayDialog = 8
                sayStage = 0
                audio.playSfx("04")
            }
            in 71..75 -> { // swords
                nowKeyFlag = 0
                val names = arrayOf("铁剑", "钢剑", "青锋剑", "圣光剑", "星光神剑")
                val adds = intArrayOf(10, 40, 70, 110, 150)
                showText("得到 ${names[t - 71]} 攻击加 ${adds[t - 71]} ！")
                audio.playSfx("01")
                setTile(nowLine, tx, ty, 0)
                nowGong += adds[t - 71]
            }
            in 76..80 -> { // shields
                nowKeyFlag = 0
                val names = arrayOf("铁盾", "钢盾", "黄金盾", "星光盾", "光芒神盾")
                val adds = intArrayOf(10, 30, 85, 120, 190)
                showText("得到 ${names[t - 76]} 防御加 ${adds[t - 76]} ！")
                audio.playSfx("01")
                setTile(nowLine, tx, ty, 0)
                nowFang += adds[t - 76]
            }
            36 -> { // key box
                nowKeyFlag = 0
                showText("得到 钥匙盒 各种钥匙数加 1 ！")
                audio.playSfx("01")
                setTile(nowLine, tx, ty, 0)
                nowYellow++
                nowBlue++
                nowRed++
            }
            39 -> { // gold
                nowKeyFlag = 0
                showText("得到 金块 金币数加 300 ！")
                audio.playSfx("01")
                setTile(nowLine, tx, ty, 0)
                nowMoney += 300
            }
            30 -> { // small feather
                nowKeyFlag = 0
                showText("得到 小飞羽 等级提升一级 ！")
                audio.playSfx("01")
                setTile(nowLine, tx, ty, 0)
                nowLife += 1
                nowHp += 1000
                nowGong += 10
                nowFang += 10
            }
            31 -> { // big feather
                nowKeyFlag = 0
                showText("得到 大飞羽 等级提升三级 ！")
                audio.playSfx("01")
                setTile(nowLine, tx, ty, 0)
                nowLife += 3
                nowHp += 3000
                nowGong += 30
                nowFang += 30
            }
            34 -> { // book 1
                nowKeyFlag = 0
                displayOther = 1
                otherFrame = 1
                audio.playSfx("04")
                setTile(nowLine, tx, ty, 0)
            }
            32 -> {
                nowKeyFlag = 0
                displayOther = 1
                otherFrame = 4
                audio.playSfx("04")
                setTile(nowLine, tx, ty, 0)
            }
            35 -> {
                nowKeyFlag = 0
                displayOther = 1
                otherFrame = 2
                audio.playSfx("04")
                setTile(nowLine, tx, ty, 0)
            }
            33 -> { // double hp
                nowKeyFlag = 0
                displayOther = 1
                otherFrame = 6
                audio.playSfx("04")
                nowHp *= 2
                setTile(nowLine, tx, ty, 0)
            }
            37 -> {
                nowKeyFlag = 0
                displayOther = 1
                otherFrame = 3
                audio.playSfx("04")
                setTile(nowLine, tx, ty, 0)
            }
            38 -> { // big clean
                nowKeyFlag = 0
                displayOther = 1
                otherFrame = 5
                audio.playSfx("04")
                bigClean = 1
                setTile(nowLine, tx, ty, 0)
            }
            22 -> { // shop
                nowKeyFlag = 0
                displayBuy = 1
                buyFrame = if (nowLine == 3) 1 else if (nowLine == 11) 3 else 1
                buyCase = 1
                audio.playSfx("04")
            }
            27 -> when (nowLine) {
                2 -> {
                    nowKeyFlag = 0
                    displaySay[1] = 1
                    sayDialog = 1
                    sayStage = 0
                    lastX = tx
                    lastY = ty
                    audio.playSfx("04")
                }
                5 -> {
                    displayBuy = 1
                    buyFrame = 4
                    buyCase = 1
                    audio.playSfx("04")
                }
                12 -> {
                    displayBuy = 1
                    buyFrame = 5
                    buyCase = 1
                    audio.playSfx("04")
                }
                15 -> if (displaySay[1] <= 3) {
                    nowKeyFlag = 0
                    displaySay[1] = 1
                    sayDialog = 1
                    sayStage = 1
                    lastX = tx
                    lastY = ty
                    audio.playSfx("04")
                }
                else -> {}
            }
            26 -> when (nowLine) {
                2 -> {
                    nowKeyFlag = 0
                    displaySay[2] = 1
                    sayDialog = 2
                    sayStage = 0
                    lastX = tx
                    lastY = ty
                    audio.playSfx("04")
                }
                5 -> {
                    displayBuy = 1
                    buyFrame = 6
                    buyCase = 1
                    audio.playSfx("04")
                }
                13 -> {
                    displayBuy = 1
                    buyFrame = 7
                    buyCase = 1
                    audio.playSfx("04")
                }
                15 -> if (displaySay[2] <= 3) {
                    nowKeyFlag = 0
                    displaySay[2] = 1
                    sayDialog = 2
                    sayStage = 1
                    lastX = tx
                    lastY = ty
                    audio.playSfx("04")
                }
                else -> {}
            }
            115 -> { // special door
                nowKeyFlag = 0
                openRoom = 1
                doorTicks = 0
                doorType = 115
                audio.playSfx("07")
                setTile(nowLine, tx, ty, 0)
                lastX = tx
                lastY = ty
            }
            25 -> if (displaySay[3] == 0) {
                nowKeyFlag = 0
                displaySay[3] = 1
                sayDialog = 3
                sayStage = 0
                audio.playSfx("04")
                setTile(2, 6, 1, 0)
            } else if (bigClean == 1) {
                nowKeyFlag = 0
                displaySay[3] = 1
                sayDialog = 3
                sayStage = 1
                setTile(18, 8, 5, 0)
                setTile(18, 9, 5, 0)
                bigClean = 0
                audio.playSfx("04")
            }
        }
    }

    private fun fightMonster(t: Int, tx: Int, ty: Int) {
        val tmpBoss = t - 40
        val b = boss[tmpBoss] ?: return
        var killable: Boolean
        if (b.fang >= nowGong) {
            killable = false
        } else if ((b.hp / (nowGong - b.fang)) * (b.gong - nowFang) > nowHp) {
            killable = false
        } else {
            killable = true
        }
        if (!killable) {
            audio.playSfx("10")
            return
        }
        nowBossId = tmpBoss
        nowKeyFlag = 0
        displayKill = 1
        when (t) {
            60 -> {
                nowHp -= 100
                audio.playSfx("20")
            }
            52 -> {
                nowHp -= 300
                audio.playSfx("24")
            }
            50 -> {
                nowHp -= nowHp / 4
                audio.playSfx("22")
            }
            57 -> {
                nowHp -= nowHp / 3
                audio.playSfx("23")
            }
        }
        killLeftHp = b.hp
        killLeftGong = b.gong
        killLeftFang = b.fang
        killRightHp = nowHp
        killRightGong = nowGong
        killRightFang = nowFang
        killNumber = 0
        killTicks = 0
        nowBossX = tx
        nowBossY = ty
        audio.playSfx("04")
    }

    // =====================================================================
    //  UI actions
    // =====================================================================

    private fun showText(msg: String) {
        textMessage = msg
        displayText = 1
        textTicks = 0
    }

    fun closeText() {
        displayText = 0
        textMessage = ""
    }

    fun closeOther() {
        displayOther = 0
        nowKeyFlag = 1
    }

    private fun buyAction(confirm: Boolean) {
        if (displayBuy != 1) return
        val case = when (buyFrame) {
            2 -> 1
            3 -> 2
            4 -> 3
            5 -> 4
            6 -> 5
            7 -> 6
            else -> 0
        }
        if (case == 0) {
            buyFrame = 2
            buyCase = 1
            return
        }
        if (confirm) {
            applyBuy(case, buyCase)
        }
    }

    private fun applyBuy(case: Int, item: Int) {
        when (case) {
            1 -> { // 25 gold shop
                if (item == 4) {
                    audio.playSfx("07")
                    closeBuy()
                    return
                }
                if (nowMoney >= 25) {
                    nowMoney -= 25
                    when (item) {
                        1 -> nowHp += 800
                        2 -> nowGong += 4
                        3 -> nowFang += 4
                    }
                    audio.playSfx("11")
                } else {
                    audio.playSfx("09")
                }
            }
            2 -> { // 100 gold shop
                if (item == 4) {
                    audio.playSfx("07")
                    closeBuy()
                    return
                }
                if (nowMoney >= 100) {
                    nowMoney -= 100
                    when (item) {
                        1 -> nowHp += 4000
                        2 -> nowGong += 20
                        3 -> nowFang += 20
                    }
                    audio.playSfx("11")
                } else {
                    audio.playSfx("09")
                }
            }
            3 -> { // buy keys
                when (item) {
                    1 -> if (nowMoney >= 10) {
                        nowMoney -= 10
                        nowYellow++
                        audio.playSfx("11")
                    } else audio.playSfx("09")
                    2 -> if (nowMoney >= 50) {
                        nowMoney -= 50
                        nowBlue++
                        audio.playSfx("11")
                    } else audio.playSfx("09")
                    3 -> if (nowMoney >= 100) {
                        nowMoney -= 100
                        nowRed++
                        audio.playSfx("11")
                    } else audio.playSfx("09")
                    4 -> {
                        audio.playSfx("07")
                        closeBuy()
                    }
                }
            }
            4 -> { // sell keys
                when (item) {
                    1 -> if (nowYellow >= 1) {
                        nowYellow--
                        nowMoney += 7
                        audio.playSfx("11")
                    } else audio.playSfx("09")
                    2 -> if (nowBlue >= 1) {
                        nowBlue--
                        nowMoney += 35
                        audio.playSfx("11")
                    } else audio.playSfx("09")
                    3 -> if (nowRed >= 1) {
                        nowRed--
                        nowMoney += 70
                        audio.playSfx("11")
                    } else audio.playSfx("09")
                    4 -> {
                        audio.playSfx("07")
                        closeBuy()
                    }
                }
            }
            5 -> { // exp shop 1
                when (item) {
                    1 -> if (nowMp >= 100) {
                        nowMp -= 100
                        nowLife += 1
                        nowHp += 1000
                        nowGong += 7
                        nowFang += 7
                        audio.playSfx("11")
                    } else audio.playSfx("09")
                    2 -> if (nowMp >= 30) {
                        nowMp -= 30
                        nowGong += 5
                        audio.playSfx("11")
                    } else audio.playSfx("09")
                    3 -> if (nowMp >= 30) {
                        nowMp -= 30
                        nowFang += 5
                        audio.playSfx("11")
                    } else audio.playSfx("09")
                    4 -> {
                        audio.playSfx("07")
                        closeBuy()
                    }
                }
            }
            6 -> { // exp shop 2
                when (item) {
                    1 -> if (nowMp >= 270) {
                        nowMp -= 270
                        nowLife += 3
                        nowHp += 3000
                        nowGong += 20
                        nowFang += 20
                        audio.playSfx("11")
                    } else audio.playSfx("09")
                    2 -> if (nowMp >= 95) {
                        nowMp -= 95
                        nowGong += 17
                        audio.playSfx("11")
                    } else audio.playSfx("09")
                    3 -> if (nowMp >= 95) {
                        nowMp -= 95
                        nowFang += 17
                        audio.playSfx("11")
                    } else audio.playSfx("09")
                    4 -> {
                        audio.playSfx("07")
                        closeBuy()
                    }
                }
            }
        }
    }

    private fun closeBuy() {
        displayBuy = 0
        nowKeyFlag = 1
    }

    fun buyMove(delta: Int) {
        buyCase += delta
        if (buyCase < 1) buyCase = 4
        if (buyCase > 4) buyCase = 1
        audio.playSfx("10")
    }

    fun buyConfirm() {
        if (buyFrame == 1) {
            buyFrame = 2
            buyCase = 1
            audio.playSfx("14")
        } else {
            buyAction(confirm = true)
        }
    }

    fun jumpMove(delta: Int) {
        jumpSelection += delta
        if (jumpSelection < 1) jumpSelection = 20
        if (jumpSelection > 20) jumpSelection = 1
        audio.playSfx("11")
    }

    // =====================================================================
    //  Say dialogs
    // =====================================================================

    fun advanceSay() {
        val idx = activeSayIdx()
        when (idx) {
            0 -> { // sister: part1 gives keys, part2 she leaves
                if (sayStage == 0) {
                    displaySay[0] = 2
                    audio.playSfx("03")
                    setTile(0, manX, manY, 0)
                    setTile(0, manX, manY - 1, 24)
                    nowYellow++
                    nowBlue++
                    nowRed++
                    displaySay[0] = 3
                    sayStage = 1
                } else {
                    displaySay[0] = 4
                    audio.playSfx("03")
                    setTile(0, manX, manY - 1, 0)
                    nowHp += nowHp / 3
                    nowGong += nowGong / 3
                    nowFang += nowFang / 3
                    displaySay[0] = 5
                    sayDialog = -1
                    nowKeyFlag = 1
                }
            }
            1 -> { // old man (shield): 黄金盾 then 星光盾
                if (sayStage == 0) {
                    displaySay[1] = 2
                    showText("得到 黄金盾 防御加 85 ！")
                    audio.playSfx("03")
                    nowFang += 85
                    setTile(2, 10, 9, 0)
                    displaySay[1] = 3
                    sayStage = 1
                } else {
                    displaySay[1] = 4
                    if (nowMoney >= 800) {
                        showText("得到 星光盾 防御加 120 ！")
                        audio.playSfx("03")
                        nowFang += 120
                        nowMoney -= 800
                        setTile(15, 3, 6, 0)
                    }
                    displaySay[1] = 5
                    sayDialog = -1
                    nowKeyFlag = 1
                }
            }
            2 -> { // old man (sword): 青锋剑 then 圣光剑
                if (sayStage == 0) {
                    displaySay[2] = 2
                    showText("得到 青锋剑 攻击加 70 ！")
                    audio.playSfx("03")
                    nowGong += 70
                    setTile(2, 10, 7, 0)
                    displaySay[2] = 3
                    sayStage = 1
                } else {
                    displaySay[2] = 4
                    if (nowMp >= 500) {
                        showText("得到 圣光剑 攻击加 110 ！")
                        audio.playSfx("03")
                        nowGong += 110
                        nowMp -= 500
                        setTile(15, 3, 4, 0)
                    }
                    displaySay[2] = 5
                    sayDialog = -1
                    nowKeyFlag = 1
                }
            }
            3 -> { // tile 25 老者
                displaySay[3] = 3
                sayDialog = -1
                nowKeyFlag = 1
            }
            6 -> { // tile 28: reveal stairs at 18F
                displaySay[6] = 2
                audio.playSfx("07")
                setTile(18, 10, 10, 13)
                displaySay[6] = 3
                sayDialog = -1
                nowKeyFlag = 1
            }
            7 -> { // tile 119 princess at 16F
                displaySay[7] = 2
                audio.playSfx("03")
                setTile(16, 4, 5, 0)
                displaySay[7] = 3
                sayDialog = -1
                nowKeyFlag = 1
            }
            8 -> { // tile 129 冥灵魔王 dialog chain
                when (sayStage) {
                    0 -> { // first talk
                        displaySay[8] = 2
                        setTile(19, 7, 5, 0)
                        displaySay[8] = 3
                        sayStage = 1
                        audio.playSfx("03")
                    }
                    1 -> { // say_two (after 19F boss killed)
                        displaySay[8] = 5
                        displaySay[8] = 6
                        sayDialog = -1
                        nowKeyFlag = 1
                        audio.playSfx("03")
                    }
                    2 -> { // say_three (final)
                        displaySay[8] = 9
                        sayDialog = -1
                        gameOver()
                        audio.playSfx("03")
                    }
                }
            }
        }
    }

    fun gameOver() {
        screen = Screen.GAME_OVER
        gameOverTicks = 0
        audio.stopAll()
    }

    // =====================================================================
    //  Line display
    // =====================================================================

    private var myLifesUp = 0
    private var myLifesDown = 0

    fun setLineDisplay(arrowId: Int) {
        displayLines = 1
        nowXid = 0
        nowYid = 0
        manX = -1
        manY = -1
        for (i in 0 until 11) {
            for (j in 0 until 11) {
                val t = tile(nowLine, i, j)
                if (t == 0) continue
                if (arrowId == 0 && t == 99) {
                    nowXid = i
                    nowYid = j
                } else if (arrowId == 1 && t == 97) {
                    nowXid = i
                    nowYid = j
                } else if (arrowId == 2 && t == 98) {
                    nowXid = i
                    nowYid = j
                }
                if (t == 24) {
                    manX = i
                    manY = j
                }
            }
        }
        if (nowLine == 21) jumpFlag = 2
        fadeDir = -1 // fade out the "第X层" banner
        lineFade = 1f
        audio.playSfx("07")
    }

    private fun playBgmForLine() {
        val bgm = when {
            nowLine == 0 -> "bgm_720"
            nowLine in 1..7 -> "bgm_721"
            nowLine in 8..14 -> "bgm_722"
            nowLine in 15..18 -> "bgm_723"
            else -> "bgm_724"
        }
        audio.playBgm(bgm)
    }

    fun lineName(): String = if (nowLine == 0) "序  章" else "第 $nowLine 层"

    // =====================================================================
    //  Update (called once per frame)
    // =====================================================================

    fun update(dtMs: Int) {
        if (screen != Screen.GAME) return
        audio.update()

        // line banner fade
        if (fadeDir != 0) {
            lineFade += fadeDir * dtMs / 600f
            if (fadeDir > 0 && lineFade >= 1f) {
                lineFade = 1f
                fadeDir = 0
                // after fade-in, apply floor change
                displayLines = 1
                if (myLifesUp == 1) {
                    myLifesUp = 0
                    nowLine++
                    if (maxLine < nowLine) maxLine = nowLine
                    setLineDisplay(1)
                    playBgmForLine()
                }
                if (myLifesDown == 1) {
                    myLifesDown = 0
                    nowLine--
                    setLineDisplay(2)
                    playBgmForLine()
                }
                nowKeyFlag = 1
            } else if (fadeDir < 0 && lineFade <= 0f) {
                lineFade = 0f
                fadeDir = 0
                displayLines = 0
                nowKeyFlag = 1
                if (nowLine == 0) {
                }
            }
        }

        // text popup timing (~1.2s like the 19-frame clip)
        if (displayText == 1) {
            textTicks += dtMs
            if (textTicks > 1200) {
                displayText = 2
                closeText()
                nowKeyFlag = 1
            }
        }

        // door opening
        if (openRoom == 1) {
            doorTicks += dtMs
            if (doorTicks > 700) {
                openRoom = 0
                nowKeyFlag = 1
            }
        }

        // battle
        if (displayKill == 1) {
            killTicks += dtMs
            if (killTicks >= 500) {
                killTicks = 0
                battleTick()
            }
        }

        // player walk animation
        playerTicks += dtMs
        if (playerTicks >= 80) {
            playerTicks = 0
            playerFrame = (playerFrame + 1) % 17
        }

        // boss boosts / endgame triggers
        if (tile(16, 5, 5) == 0 && setMan01 == 0) {
            setMan01 = 1
            val b = boss[13] ?: return
            boss[13] = BossData(b.name, b.hp + b.hp / 3, b.gong + b.gong / 3, b.fang + b.fang / 3, b.money, b.exp)
        }
        if (tile(19, 6, 5) == 0 && setMan02 == 0) {
            setMan02 = 1
            val b = boss[19] ?: return
            boss[19] = BossData(b.name, b.hp + b.hp / 2, b.gong + b.gong / 2, b.fang + b.fang / 2, b.money, b.exp)
            nowKeyFlag = 0
            displaySay[8] = 1
            sayDialog = 8
            sayStage = 1
            audio.playSfx("04")
        }
        if (tile(21, 1, 5) == 0 && displayKills == 0) {
            displayKills = 1
            nowKeyFlag = 0
            displaySay[8] = 1
            sayDialog = 8
            sayStage = 2
            audio.playSfx("04")
        }

        // list panel
        if (displayList == 1) {
            buildMonsterList()
        } else if (displayList == 3) {
            displayList = 0
            nowKeyFlag = 1
        }

        // jump panel confirm / cancel
        if (displayJump == 2) {
            displayJump = 0
            setLineDisplay(1)
            nowKeyFlag = 0
        } else if (displayJump == 4) {
            displayJump = 0
            nowKeyFlag = 1
        }
    }

    private fun battleTick() {
        if (displayKill != 1) return
        if (killLeftHp > 0 && killRightHp > 0) {
            if (killNumber % 2 == 0) {
                var dmg = killRightGong - killLeftFang
                if (dmg < 0) dmg = 0
                // crit chance based on level
                critRoll = (critRoll * 1103515245 + 12345) ushr 16
                if (critRoll % 100 <= nowLife) {
                    dmg *= 2
                }
                killLeftHp -= dmg
                if (killLeftHp < 0) killLeftHp = 0
                audio.playSfx("05")
            } else {
                var dmg = killLeftGong - killRightFang
                if (dmg < 0) dmg = 0
                killRightHp -= dmg
                if (killLeftHp <= 0) {
                    // victory
                }
                if (killLeftGong > killRightFang) audio.playSfx("12") else audio.playSfx("13")
            }
            killNumber++
        }
        if (killLeftHp <= 0) {
            // victory
            audio.playSfx("07")
            displayKill = 2
            nowHp = killRightHp
            // reward
            val b = boss[nowBossId]
            if (b != null) {
                showText("得到金币数 ${b.money} 经验值 ${b.exp} ！")
                audio.playSfx("03")
                setTile(nowLine, nowBossX, nowBossY, 0)
                nowMoney += b.money
                nowMp += b.exp
            }
            displayKill = 0
            nowKeyFlag = 1
        }
    }

    private fun buildMonsterList() {
        val tmp = ArrayList<Int>()
        for (i in 0 until 11) {
            for (j in 0 until 11) {
                val t = tile(nowLine, i, j)
                if (t in 40..70 && !tmp.contains(t)) {
                    tmp.add(t)
                }
            }
        }
        monsterList = tmp
        if (monsterList.isEmpty()) {
            audio.playSfx("08")
            displayList = 0
            nowKeyFlag = 1
        } else {
            displayList = 2
        }
    }
}
