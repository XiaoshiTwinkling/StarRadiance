#!/usr/bin/env python3
"""Adds the star-chart translation keys to en_us.json and zh_cn.json.

The star chart needs a name for every one of the 88 IAU constellations plus the brightest star
names, which is too much to maintain by hand. This tool owns that data, checks the IAU ids against
the shipped star catalogue and appends whatever is missing, leaving the hand-written sections of
the two lang files untouched.

    python tools/build_chart_lang.py
"""

import json
import os
import struct
import sys

LANG_DIR = os.path.join("src", "client", "resources", "assets", "starradiance", "lang")
META_PATH = os.path.join("src", "client", "resources", "assets", "starradiance", "sky",
                         "stars_meta.dat")

# key -> (en_us, zh_cn)
UI_KEYS = {
    "key.starradiance.starchart": ("Open star chart", "打开星空辅助"),
    "screen.starradiance.starchart.title": ("Star watching assistant", "星空辅助系统"),
    "hud.starradiance.chart.hint":
        ("U/Esc: close   R: reset   drag: pan   wheel: zoom   Shift: %1$s",
         "U/Esc：关闭   R：复位   拖动：平移   滚轮：缩放   Shift：%1$s"),
    "hud.starradiance.chart.constellations.show": ("show constellations", "显示星座连线"),
    "hud.starradiance.chart.constellations.hide": ("hide constellations", "隐藏星座连线"),
    "hud.starradiance.chart.stats": ("mag<=%1$s  stars %2$s  zoom %3$sx",
                                     "星等≤%1$s  星数 %2$s  缩放 %3$s×"),
    "hud.starradiance.chart.click": ("Click a star for its data", "点击星星查看资料"),
    "hud.starradiance.chart.mark.hint": ("hold to mark it in the sky", "长按标记到天空"),
    "hud.starradiance.chart.mark.done": ("marked in the sky: %1$s", "已在天空标记：%1$s"),
    "hud.starradiance.chart.field.type": ("Type", "类型"),
    "hud.starradiance.chart.field.size": ("Size", "大小"),
    "hud.starradiance.chart.value.size": ("%1$s arcmin", "%1$s 角分"),
    "hud.starradiance.deepsky.type.galaxy": ("galaxy", "星系"),
    "hud.starradiance.deepsky.type.open": ("open cluster", "疏散星团"),
    "hud.starradiance.deepsky.type.globular": ("globular cluster", "球状星团"),
    "hud.starradiance.deepsky.type.nebula": ("nebula", "星云"),
    "hud.starradiance.deepsky.type.planetary": ("planetary nebula", "行星状星云"),
    "hud.starradiance.deepsky.type.remnant": ("supernova remnant", "超新星遗迹"),
    "hud.starradiance.chart.dataMissing": ("Star data file unavailable", "星表资料不可用"),
    "hud.starradiance.chart.field.designation": ("Designation", "编号"),
    "hud.starradiance.chart.field.constellation": ("Constellation", "星座"),
    "hud.starradiance.chart.field.magnitude": ("Magnitude", "视星等"),
    "hud.starradiance.chart.field.color": ("B-V / spectral", "色指数 / 光谱型"),
    "hud.starradiance.chart.field.distance": ("Distance", "距离"),
    "hud.starradiance.chart.field.radec": ("RA / Dec (J2000)", "赤经 / 赤纬 (J2000)"),
    "hud.starradiance.chart.field.altaz": ("Altitude / azimuth", "高度 / 方位"),
    "hud.starradiance.chart.value.distance": ("%1$s ly", "%1$s 光年"),
    "hud.starradiance.chart.value.unknown": ("-", "-"),
    "hud.starradiance.chart.daylight": ("Daylight - nothing to observe", "白昼无法观测"),
    "hud.starradiance.chart.daylight.sunAlt": ("Sun altitude %1$s", "太阳高度 %1$s"),
    "hud.starradiance.chart.daylight.sunset": ("Sunset in %1$s min", "%1$s 分钟后日落"),
    "hud.starradiance.chart.daylight.never": ("The sun does not set today", "今日太阳不落"),
    "hud.starradiance.chart.dir.n": ("N", "北"),
    "hud.starradiance.chart.dir.e": ("E", "东"),
    "hud.starradiance.chart.dir.s": ("S", "南"),
    "hud.starradiance.chart.dir.w": ("W", "西"),
    "hud.starradiance.planet.mercury": ("Mercury", "水星"),
    "hud.starradiance.planet.venus": ("Venus", "金星"),
    "hud.starradiance.planet.earth": ("Earth", "地球"),
    "hud.starradiance.planet.mars": ("Mars", "火星"),
    "hud.starradiance.planet.jupiter": ("Jupiter", "木星"),
    "hud.starradiance.planet.saturn": ("Saturn", "土星"),
    "hud.starradiance.planet.uranus": ("Uranus", "天王星"),
    "hud.starradiance.planet.neptune": ("Neptune", "海王星"),
    "hud.starradiance.planet.pluto": ("Pluto", "冥王星"),
    "hud.starradiance.planet.ceres": ("Ceres", "谷神星"),
    "hud.starradiance.planet.eris": ("Eris", "阋神星"),
    "hud.starradiance.planet.haumea": ("Haumea", "妊神星"),
    "hud.starradiance.planet.makemake": ("Makemake", "鸟神星"),
    "hud.starradiance.planet.halley": ("Halley's comet", "哈雷彗星"),
    "hud.starradiance.diagram.zoom": ("zoom %1$sx", "缩放 %1$s×"),
    "hud.starradiance.diagram.hint":
        ("drag: move   wheel: zoom   R: reset   click a planet",
         "拖动平移   滚轮缩放   R 复位   点击星球查看"),
}

# IAU abbreviation -> (English name, Chinese name)
CONSTELLATIONS = {
    "And": ("Andromeda", "仙女座"),
    "Ant": ("Antlia", "唧筒座"),
    "Aps": ("Apus", "天燕座"),
    "Aqr": ("Aquarius", "宝瓶座"),
    "Aql": ("Aquila", "天鹰座"),
    "Ara": ("Ara", "天坛座"),
    "Ari": ("Aries", "白羊座"),
    "Aur": ("Auriga", "御夫座"),
    "Boo": ("Bootes", "牧夫座"),
    "Cae": ("Caelum", "雕具座"),
    "Cam": ("Camelopardalis", "鹿豹座"),
    "Cnc": ("Cancer", "巨蟹座"),
    "CVn": ("Canes Venatici", "猎犬座"),
    "CMa": ("Canis Major", "大犬座"),
    "CMi": ("Canis Minor", "小犬座"),
    "Cap": ("Capricornus", "摩羯座"),
    "Car": ("Carina", "船底座"),
    "Cas": ("Cassiopeia", "仙后座"),
    "Cen": ("Centaurus", "半人马座"),
    "Cep": ("Cepheus", "仙王座"),
    "Cet": ("Cetus", "鲸鱼座"),
    "Cha": ("Chamaeleon", "蝘蜓座"),
    "Cir": ("Circinus", "圆规座"),
    "Col": ("Columba", "天鸽座"),
    "Com": ("Coma Berenices", "后发座"),
    "CrA": ("Corona Australis", "南冕座"),
    "CrB": ("Corona Borealis", "北冕座"),
    "Crv": ("Corvus", "乌鸦座"),
    "Crt": ("Crater", "巨爵座"),
    "Cru": ("Crux", "南十字座"),
    "Cyg": ("Cygnus", "天鹅座"),
    "Del": ("Delphinus", "海豚座"),
    "Dor": ("Dorado", "剑鱼座"),
    "Dra": ("Draco", "天龙座"),
    "Equ": ("Equuleus", "小马座"),
    "Eri": ("Eridanus", "波江座"),
    "For": ("Fornax", "天炉座"),
    "Gem": ("Gemini", "双子座"),
    "Gru": ("Grus", "天鹤座"),
    "Her": ("Hercules", "武仙座"),
    "Hor": ("Horologium", "时钟座"),
    "Hya": ("Hydra", "长蛇座"),
    "Hyi": ("Hydrus", "水蛇座"),
    "Ind": ("Indus", "印第安座"),
    "Lac": ("Lacerta", "蝎虎座"),
    "Leo": ("Leo", "狮子座"),
    "LMi": ("Leo Minor", "小狮座"),
    "Lep": ("Lepus", "天兔座"),
    "Lib": ("Libra", "天秤座"),
    "Lup": ("Lupus", "豺狼座"),
    "Lyn": ("Lynx", "天猫座"),
    "Lyr": ("Lyra", "天琴座"),
    "Men": ("Mensa", "山案座"),
    "Mic": ("Microscopium", "显微镜座"),
    "Mon": ("Monoceros", "麒麟座"),
    "Mus": ("Musca", "苍蝇座"),
    "Nor": ("Norma", "矩尺座"),
    "Oct": ("Octans", "南极座"),
    "Oph": ("Ophiuchus", "蛇夫座"),
    "Ori": ("Orion", "猎户座"),
    "Pav": ("Pavo", "孔雀座"),
    "Peg": ("Pegasus", "飞马座"),
    "Per": ("Perseus", "英仙座"),
    "Phe": ("Phoenix", "凤凰座"),
    "Pic": ("Pictor", "绘架座"),
    "Psc": ("Pisces", "双鱼座"),
    "PsA": ("Piscis Austrinus", "南鱼座"),
    "Pup": ("Puppis", "船尾座"),
    "Pyx": ("Pyxis", "罗盘座"),
    "Ret": ("Reticulum", "网罟座"),
    "Sge": ("Sagitta", "天箭座"),
    "Sgr": ("Sagittarius", "人马座"),
    "Sco": ("Scorpius", "天蝎座"),
    "Scl": ("Sculptor", "玉夫座"),
    "Sct": ("Scutum", "盾牌座"),
    "Ser": ("Serpens", "巨蛇座"),
    "Sex": ("Sextans", "六分仪座"),
    "Tau": ("Taurus", "金牛座"),
    "Tel": ("Telescopium", "望远镜座"),
    "Tri": ("Triangulum", "三角座"),
    "TrA": ("Triangulum Australe", "南三角座"),
    "Tuc": ("Tucana", "杜鹃座"),
    "UMa": ("Ursa Major", "大熊座"),
    "UMi": ("Ursa Minor", "小熊座"),
    "Vel": ("Vela", "船帆座"),
    "Vir": ("Virgo", "室女座"),
    "Vol": ("Volans", "飞鱼座"),
    "Vul": ("Vulpecula", "狐狸座"),
}

# HYG "proper" name -> Chinese name (only the famous ones; everything else keeps the original).
# Deep-sky objects that have a real Chinese name; the rest keep the catalogue's English name.
DEEP_SKY = {
    "M1": ("Crab Nebula", "蟹状星云"),
    "M6": ("Butterfly Cluster", "蝴蝶星团"),
    "M7": ("Ptolemy Cluster", "托勒密星团"),
    "M8": ("Lagoon Nebula", "礁湖星云"),
    "M11": ("Wild Duck Cluster", "野鸭星团"),
    "M13": ("Hercules Cluster", "武仙座大星团"),
    "M16": ("Eagle Nebula", "鹰状星云"),
    "M17": ("Omega Nebula", "天鹅星云"),
    "M20": ("Trifid Nebula", "三叶星云"),
    "M22": ("M22", "人马座球状星团"),
    "M27": ("Dumbbell Nebula", "哑铃星云"),
    "M31": ("Andromeda Galaxy", "仙女座星系"),
    "M33": ("Triangulum Galaxy", "三角座星系"),
    "M42": ("Orion Nebula", "猎户座大星云"),
    "M43": ("De Mairan's Nebula", "德梅朗星云"),
    "M44": ("Beehive Cluster", "蜂巢星团"),
    "M45": ("Pleiades", "昴星团"),
    "M51": ("Whirlpool Galaxy", "涡状星系"),
    "M57": ("Ring Nebula", "环状星云"),
    "M63": ("Sunflower Galaxy", "向日葵星系"),
    "M64": ("Black Eye Galaxy", "黑眼星系"),
    "M76": ("Little Dumbbell", "小哑铃星云"),
    "M81": ("Bode's Galaxy", "波德星系"),
    "M82": ("Cigar Galaxy", "雪茄星系"),
    "M83": ("Southern Pinwheel", "南风车星系"),
    "M87": ("Virgo A", "室女座A星系"),
    "M97": ("Owl Nebula", "猫头鹰星云"),
    "M101": ("Pinwheel Galaxy", "风车星系"),
    "M104": ("Sombrero Galaxy", "草帽星系"),
    "NGC104": ("47 Tucanae", "杜鹃座47"),
    "NGC253": ("Sculptor Galaxy", "玉夫座星系"),
    "NGC869": ("Double Cluster", "英仙座双星团"),
    "NGC5128": ("Centaurus A", "半人马座A"),
    "NGC5139": ("Omega Centauri", "半人马座ω星团"),
    "LMC": ("Large Magellanic Cloud", "大麦哲伦云"),
    "SMC": ("Small Magellanic Cloud", "小麦哲伦云"),
    "Hyades": ("Hyades", "毕星团"),
}

STARS = {
    "Sirius": "天狼星",
    "Canopus": "老人星",
    "Arcturus": "大角星",
    "Rigil Kentaurus": "南门二",
    "Toliman": "南门一",
    "Vega": "织女一",
    "Capella": "五车二",
    "Rigel": "参宿七",
    "Procyon": "南河三",
    "Betelgeuse": "参宿四",
    "Achernar": "水委一",
    "Hadar": "马腹一",
    "Altair": "河鼓二",
    "Acrux": "十字架二",
    "Aldebaran": "毕宿五",
    "Antares": "心宿二",
    "Spica": "角宿一",
    "Pollux": "北河三",
    "Fomalhaut": "北落师门",
    "Deneb": "天津四",
    "Mimosa": "十字架三",
    "Regulus": "轩辕十四",
    "Adhara": "弧矢七",
    "Castor": "北河二",
    "Gacrux": "十字架一",
    "Shaula": "尾宿八",
    "Bellatrix": "参宿五",
    "Elnath": "五车五",
    "Miaplacidus": "南船五",
    "Alnilam": "参宿二",
    "Alnitak": "参宿一",
    "Alnair": "鹤一",
    "Alioth": "北斗五",
    "Dubhe": "北斗一",
    "Mirfak": "天船三",
    "Wezen": "弧矢一",
    "Alkaid": "北斗七",
    "Avior": "海石一",
    "Sargas": "尾宿五",
    "Menkalinan": "五车三",
    "Atria": "三角形三",
    "Alhena": "井宿三",
    "Peacock": "孔雀十一",
    "Polaris": "勾陈一",
    "Mirzam": "军市一",
    "Alphard": "星宿一",
    "Algieba": "轩辕十二",
    "Hamal": "娄宿三",
    "Diphda": "土司空",
    "Nunki": "斗宿四",
    "Menkent": "库楼三",
    "Alpheratz": "壁宿二",
    "Mirach": "奎宿九",
    "Kochab": "北极二",
    "Saiph": "参宿六",
    "Rasalhague": "侯",
    "Algol": "大陵五",
    "Almach": "天大将军一",
    "Denebola": "五帝座一",
    "Aspidiske": "海石二",
    "Naos": "弧矢增二十二",
    "Alphecca": "贯索四",
    "Mizar": "开阳",
    "Sadr": "天津一",
    "Eltanin": "天棓四",
}


def catalog_ids():
    """Reads the IAU id list out of stars_meta.dat so the tool notices a catalogue change."""
    with open(META_PATH, "rb") as handle:
        data = handle.read()
    if data[0:4] != b"NWSM":
        raise ValueError("stars_meta.dat has an invalid header")
    count = struct.unpack_from(">i", data, 8)[0]
    offset = 12
    ids = []
    for _ in range(count):
        length = struct.unpack_from(">i", data, offset)[0]
        offset += 4
        ids.append(data[offset:offset + length].decode("utf-8"))
        offset += length
    return ids


def entries():
    """The full (key, en, zh) list, in a stable order."""
    out = []
    for key, (english, chinese) in UI_KEYS.items():
        out.append((key, english, chinese))
    for abbreviation, (english, chinese) in CONSTELLATIONS.items():
        out.append((f"constellation.starradiance.{abbreviation}", english, chinese))
    for object_id, (english, chinese) in DEEP_SKY.items():
        out.append((f"deepsky.starradiance.{object_id}", english, chinese))
    for name, chinese in STARS.items():
        out.append((f"star.starradiance.{name}", name, chinese))
    return out


def splice(path, new_entries):
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    table = json.loads(text)
    added = [entry for entry in new_entries if entry[0] not in table]
    if not added:
        print(f"  {path}: up to date ({len(table)} keys)")
        return 0
    closing = text.rindex("}")
    head = text[:closing].rstrip()
    if not head.endswith(","):
        head += ","
    lines = []
    for index, (key, english, chinese) in enumerate(added):
        value = english if path.endswith("en_us.json") else chinese
        comma = "," if index + 1 < len(added) else ""
        lines.append(f"    {json.dumps(key, ensure_ascii=False)}: "
                     f"{json.dumps(value, ensure_ascii=False)}{comma}")
    text = head + "\n\n" + "\n".join(lines) + "\n}\n"
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(text)
    print(f"  {path}: added {len(added)} keys ({len(table) + len(added)} total)")
    return len(added)


def main():
    if not os.path.exists(META_PATH):
        print("run tools/build_star_catalog.py first", file=sys.stderr)
        return 1
    ids = catalog_ids()
    missing = [name for name in ids if name not in CONSTELLATIONS]
    extra = [name for name in CONSTELLATIONS if name not in ids]
    if missing or extra:
        print(f"FAIL constellation mismatch: missing={missing} extra={extra}", file=sys.stderr)
        return 1
    print(f"catalogue: {len(ids)} constellations, {len(STARS)} Chinese star names")
    table = entries()
    splice(os.path.join(LANG_DIR, "en_us.json"), table)
    splice(os.path.join(LANG_DIR, "zh_cn.json"), table)
    return 0


if __name__ == "__main__":
    sys.exit(main())
