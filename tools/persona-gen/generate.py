#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Task 7: 虚拟账号人设种子生成器。

功能：
- 基于 职业(20) x 年龄段(4) x 文化背景(6) 矩阵抽样 220 条人设
- 每条人设填充固定字段（id/displayName/username/bio/worldview/values 等）
- 每条人设附带 5-10 条历史推文（模板组合，不调用 LLM）
- 输出 11 个分片文件 personas_001.json ~ personas_011.json
- 输出 index.json 汇总文件
- 生成 assets/avatars/ 目录与 index.txt（账号 ID 列表）

注意：本脚本代码与注释中不包含任何 emoji；
emojiPreference 字段中的 emoji 属于数据内容，按规范保留。
"""

from __future__ import annotations

import json
import random
import uuid
from pathlib import Path
from typing import Dict, List, Tuple

# --------------------------------------------------------------------------- #
# 常量定义
# --------------------------------------------------------------------------- #

REPO_ROOT = Path(__file__).resolve().parents[2]
PERSONAS_DIR = REPO_ROOT / "app" / "src" / "main" / "assets" / "personas"
AVATARS_DIR = REPO_ROOT / "app" / "src" / "main" / "assets" / "avatars"

TOTAL_PERSONAS = 220
SHARD_SIZE = 20
RANDOM_SEED = 20240705

PROFESSIONS: List[str] = [
    "程序员", "设计师", "教师", "医生", "律师", "记者", "厨师", "摄影师",
    "音乐人", "作家", "运动员", "创业者", "公务员", "护士", "工程师",
    "研究员", "学生", "退休者", "艺术家", "自由职业",
]

AGE_RANGES: List[str] = ["18-24", "25-34", "35-44", "45+"]

CULTURAL_BACKGROUNDS: List[str] = [
    "华北", "华东", "华南", "西南", "西北", "海外华人",
]

LANGUAGE_STYLES: List[str] = [
    "正式", "口语", "幽默", "犀利", "温和", "文艺", "直率",
]

CATCHPHRASE_POOL: List[str] = [
    "破防了", "绝绝子", "笑死", "格局打开", "emo 了",
    "yyds", "栓 Q", "芭比 Q",
]

MEDIA_THEMES: List[str] = [
    "landscape", "food", "city", "pet", "sport", "art", "tech", "nature",
]

# username 中的职业短码（英文，便于构成合法用户名）
PROFESSION_CODE: Dict[str, str] = {
    "程序员": "dev", "设计师": "design", "教师": "edu", "医生": "doc",
    "律师": "law", "记者": "jour", "厨师": "chef", "摄影师": "photo",
    "音乐人": "music", "作家": "writer", "运动员": "sport", "创业者": "biz",
    "公务员": "gov", "护士": "nurse", "工程师": "eng", "研究员": "sci",
    "学生": "stu", "退休者": "retire", "艺术家": "art", "自由职业": "free",
}

# emojiPreference 中的 emoji 属于数据内容，按规范保留
EMOJI_BY_AGE: Dict[str, List[str]] = {
    "18-24": ["\U0001f923", "\u2728", "\U0001f62d", "\U0001f64c", "\U0001f605",
              "\U0001f4aa", "\U0001f97a", "\U0001f525"],
    "25-34": ["\U0001f602", "\U0001f44d", "\U0001f525", "\U0001f4af",
              "\U0001f64c", "\u2728"],
    "35-44": ["\U0001f44d", "\U0001f60a", "\U0001f339", "\U0001f64f"],
    "45+":   ["\U0001f60a", "\U0001f44d", "\U0001f375", "\U0001f305"],
}

# --------------------------------------------------------------------------- #
# 姓名池（按文化背景）+ 拼音首字母映射
# --------------------------------------------------------------------------- #

SURNAME_INITIAL: Dict[str, str] = {
    "张": "z", "王": "w", "李": "l", "赵": "z", "刘": "l", "陈": "c",
    "吴": "w", "沈": "s", "陆": "l", "林": "l", "黄": "h", "梁": "l",
    "何": "h", "郑": "z", "杨": "y", "罗": "l", "马": "m", "周": "z",
    "徐": "x", "孙": "s", "朱": "z", "高": "g", "韩": "h", "冯": "f",
}

GIVEN_INITIAL: Dict[str, str] = {
    "伟": "w", "磊": "l", "洋": "y", "勇": "y", "杰": "j", "健": "j",
    "明": "m", "华": "h", "辉": "h", "文": "w", "军": "j", "虎": "h",
    "波": "b", "刚": "g", "志": "z", "强": "q", "鹏": "p", "涛": "t",
    "宇": "y", "晨": "c", "浩": "h", "轩": "x", "芳": "f", "娜": "n",
    "敏": "m", "静": "j", "艳": "y", "婷": "t", "雪": "x", "慧": "h",
    "玲": "l", "燕": "y", "梅": "m", "兰": "l", "霞": "x", "丽": "l",
    "娟": "j", "颖": "y", "倩": "q", "莹": "y", "琳": "l", "悦": "y",
    "欣": "x", "彤": "t", "瑶": "y", "辰": "c", "翔": "x", "凯": "k",
}

SURNAMES_BY_CULTURE: Dict[str, List[str]] = {
    "华北": ["张", "王", "李", "赵", "刘", "周", "孙", "高"],
    "华东": ["陈", "王", "张", "刘", "吴", "沈", "陆", "徐"],
    "华南": ["陈", "林", "黄", "梁", "何", "郑", "李", "吴"],
    "西南": ["李", "王", "张", "杨", "刘", "罗", "陈", "周"],
    "西北": ["马", "王", "李", "张", "杨", "赵", "刘", "陈"],
    "海外华人": ["林", "陈", "黄", "李", "王", "张", "刘", "吴"],
}

GIVEN_BY_CULTURE: Dict[str, List[str]] = {
    "华北": ["伟", "磊", "洋", "勇", "杰", "芳", "娜", "敏", "静", "艳",
            "军", "霞", "刚", "鹏", "婷", "辉"],
    "华东": ["婷", "雪", "慧", "健", "明", "华", "玲", "燕", "磊", "杰",
            "颖", "倩", "莹", "浩", "轩", "欣"],
    "华南": ["伟", "强", "丽", "娟", "辉", "文", "志", "杰", "敏", "静",
            "勇", "艳", "波", "梅", "刚", "翔"],
    "西南": ["敏", "静", "勇", "艳", "波", "梅", "刚", "涛", "宇", "晨",
            "兰", "霞", "丽", "娟", "凯", "彤"],
    "西北": ["军", "霞", "虎", "兰", "文", "海", "刚", "鹏", "梅", "艳",
            "勇", "芳", "磊", "辉", "志", "燕"],
    "海外华人": ["伟", "明", "华", "文", "杰", "婷", "雪", "慧", "玲", "燕",
                "欣", "彤", "瑶", "辰", "翔", "凯"],
}

# 海外华人可使用的英文名（与姓氏组合）
ENGLISH_NAMES: List[Tuple[str, str]] = [
    ("Kevin", "m"), ("David", "m"), ("Tony", "m"), ("Eric", "m"),
    ("Alex", "m"), ("Jason", "m"), ("Brian", "m"), ("Louis", "m"),
    ("Jenny", "f"), ("Lisa", "f"), ("Amy", "f"), ("Anna", "f"),
    ("Linda", "f"), ("Sara", "f"), ("Cathy", "f"), ("Mia", "f"),
]

# --------------------------------------------------------------------------- #
# activeWindows：24 槽 bool 数组（下标 0-23 对应小时）
# key = 职业，value = [(start, end), ...] 闭区间小时范围
# --------------------------------------------------------------------------- #

ACTIVE_WINDOWS_BY_PROFESSION: Dict[str, List[Tuple[int, int]]] = {
    "程序员": [(9, 12), (14, 17), (20, 23)],
    "设计师": [(10, 12), (14, 17), (20, 22)],
    "教师": [(7, 11), (14, 21)],
    "医生": [(7, 17)],
    "律师": [(9, 11), (14, 17), (20, 21)],
    "记者": [(8, 11), (14, 17), (20, 22)],
    "厨师": [(6, 9), (16, 21)],
    "摄影师": [(9, 11), (15, 17), (20, 21)],
    "音乐人": [(10, 13), (16, 17), (21, 23)],
    "作家": [(9, 12), (15, 17), (22, 23)],
    "运动员": [(6, 8), (16, 18)],
    "创业者": [(8, 11), (14, 17), (20, 22)],
    "公务员": [(8, 11), (14, 17)],
    "护士": [(7, 11), (14, 18)],
    "工程师": [(9, 11), (14, 17)],
    "研究员": [(9, 11), (14, 17), (20, 21)],
    "学生": [(8, 11), (14, 16), (19, 22)],
    "退休者": [(6, 9), (14, 19)],
    "艺术家": [(10, 13), (16, 19), (22, 23)],
    "自由职业": [(10, 13), (15, 18), (21, 22)],
}

# --------------------------------------------------------------------------- #
# 模板池：bio / worldview / values
# --------------------------------------------------------------------------- #

BIO_PROFESSION: Dict[str, List[str]] = {
    "程序员": ["代码与咖啡，构建数字世界", "用逻辑搭建产品，用键盘记录生活"],
    "设计师": ["把美感揉进每一个像素", "在留白与色彩之间寻找平衡"],
    "教师": ["三尺讲台，与少年同行", "教书育人，也在被学生治愈"],
    "医生": ["白大褂下的温度与责任", "守护生命，也敬畏生命"],
    "律师": ["以法为尺，丈量公平", "在条文与人性间寻找答案"],
    "记者": ["记录时代，也记录普通人", "用文字为真相留痕"],
    "厨师": ["烟火气里藏着热爱", "一灶一锅，烹出人间百味"],
    "摄影师": ["用镜头收藏光影与瞬间", "快门按下，时间被定格"],
    "音乐人": ["把情绪写进旋律里", "音符是我的第二种语言"],
    "作家": ["以字为砖，砌一座心城", "在故事里安放灵魂"],
    "运动员": ["汗水是成绩的底色", "在极限边缘一次次出发"],
    "创业者": ["从零到一的执拗与热爱", "在不确定中寻找确定性"],
    "公务员": ["认真做事，踏实为人", "在体制内做具体的人"],
    "护士": ["夜班里也有星光", "用耐心守护每一份不安"],
    "工程师": ["让图纸落地成现实", "在精度与稳妥间反复推敲"],
    "研究员": ["在未知里寻找答案", "用实验与数据对话世界"],
    "学生": ["还在认真长大", "课本之外，也想看看世界"],
    "退休者": ["退休不是结束，是新的开始", "慢慢生活，细细回味"],
    "艺术家": ["把感受变成可见的形状", "在色彩与材质间流浪"],
    "自由职业": ["自由是另一种自律", "时间自己安排，生活自己写"],
}

BIO_AGE_SUFFIX: Dict[str, List[str]] = [
    ("18-24", ["，刚上路。", "，未来还长。"]),
    ("25-34", ["，正在路上。", "，渐入佳境。"]),
    ("35-44", ["，已见风雨。", "，沉淀中前行。"]),
    ("45+",   ["，回头看仍热爱。", "，慢享岁月。"]),
]

WORLDVIEW_PROFESSION: Dict[str, List[str]] = {
    "程序员": ["相信技术能让生活更公平", "认为代码逻辑也是一种秩序", "相信开源与协作改变世界"],
    "设计师": ["相信美感能提升日常的尊严", "认为设计是解决问题的善意", "相信细节决定体验"],
    "教师": ["相信教育是慢的艺术", "认为每个孩子都有光", "相信陪伴比说教有力"],
    "医生": ["相信科学与人文不可分", "认为生命面前人人平等", "相信预防胜于治疗"],
    "律师": ["相信规则是社会运转的底线", "认为程序正义不可或缺", "相信法律应有人情温度"],
    "记者": ["相信记录本身就是力量", "认为真相值得被看见", "相信多元视角抵消偏见"],
    "厨师": ["相信烟火气最抚凡人心", "认为认真做饭是种修行", "相信食物连接记忆"],
    "摄影师": ["相信瞬间即永恒", "认为光影会替人说话", "相信观察比评判重要"],
    "音乐人": ["相信旋律能抵达语言到不了的地方", "认为情绪需要出口", "相信共鸣跨越距离"],
    "作家": ["相信故事让人不孤单", "认为文字是另一种抵抗", "相信想象力是自由"],
    "运动员": ["相信坚持比天赋更可靠", "认为身体是诚实的伙伴", "相信失败是必经的台阶"],
    "创业者": ["相信做成事需要长期主义", "认为不确定性是机会", "相信团队胜过个人"],
    "公务员": ["相信把事做实在就是贡献", "认为规则之内也可有为", "相信服务是种价值"],
    "护士": ["相信细节里的关怀最珍贵", "认为耐心也是治疗", "相信同理心减轻痛苦"],
    "工程师": ["相信稳妥比炫技更重要", "认为工程是取舍的艺术", "相信数据驱动决策"],
    "研究员": ["相信好奇心推动世界", "认为真理值得慢慢靠近", "相信质疑是科学的底色"],
    "学生": ["相信努力会有回响", "认为世界值得被探索", "相信可能性比答案重要"],
    "退休者": ["相信平淡里有真味", "认为健康是最大的财富", "相信时间自有答案"],
    "艺术家": ["相信感受值得被表达", "认为美没有标准答案", "相信创作是与自己对话"],
    "自由职业": ["相信自由需要自律支撑", "认为生活不应只有一种节奏", "相信多元更接近真实"],
}

WORLDVIEW_AGE_MODIFIER: Dict[str, List[str]] = {
    "18-24": ["，未来充满可能", "，正学着与世界相处"],
    "25-34": ["，正在路上慢慢验证", "，野心与迷茫并存"],
    "35-44": ["，已见风雨仍想前行", "，开始懂得取舍"],
    "45+":   ["，回头看仍愿意相信", "，更愿意慢慢来"],
}

VALUES_CULTURE: Dict[str, List[str]] = {
    "华北": ["重情义、讲规矩", "看重面子和信义", "崇尚踏实与直爽"],
    "华东": ["重契约、讲分寸", "看重效率与体面", "崇尚精致与务实"],
    "华南": ["重宗族、讲务实", "看重开源与拼搏", "崇尚灵活与变通"],
    "西南": ["重人情、讲豁达", "看重生活与滋味", "崇尚自在与包容"],
    "西北": ["重厚道、讲韧劲", "看重家与传承", "崇尚朴实与坚毅"],
    "海外华人": ["重身份认同、讲连接", "看重中西兼容", "崇尚独立与根脉并重"],
}

VALUES_PROFESSION: Dict[str, List[str]] = {
    "程序员": ["以代码为业，信守工程伦理", "崇尚简洁与可维护"],
    "设计师": ["以用户体验为先", "信守审美与功能并重"],
    "教师": ["以学生成长为重", "信守言传身教"],
    "医生": ["以患者为先", "信守职业操守"],
    "律师": ["以当事人利益与正义为重", "信守保密与独立"],
    "记者": ["以客观记录为重", "信守事实与公共性"],
    "厨师": ["以食客满意为重", "信守食材与火候"],
    "摄影师": ["以真实瞬间为重", "信守尊重被摄者"],
    "音乐人": ["以真诚表达为重", "信守原创与共鸣"],
    "作家": ["以文字诚实为重", "信守想象力与责任"],
    "运动员": ["以公平竞技为重", "信守纪律与拼搏"],
    "创业者": ["以创造价值为重", "信守长期与诚信"],
    "公务员": ["以服务公众为重", "信守规矩与担当"],
    "护士": ["以患者舒适为重", "信守耐心与细心"],
    "工程师": ["以安全可靠为重", "信守精度与稳健"],
    "研究员": ["以求真为重", "信守可重复与同行评议"],
    "学生": ["以学习与探索为重", "信守诚实与好奇"],
    "退休者": ["以家庭与健康为重", "信守平和与知足"],
    "艺术家": ["以表达自由为重", "信守真诚与边界"],
    "自由职业": ["以专业交付为重", "信守契约与自律"],
}

# --------------------------------------------------------------------------- #
# 历史推文模板：场景（按职业）+ 结尾（按语言风格）
# 每个职业 scenes x tails 组合 >= 20 个模板
# --------------------------------------------------------------------------- #

TWEET_SCENES: Dict[str, List[str]] = {
    "程序员": [
        "今天的 bug 是昨天的 feature", "需求又变了第三版", "重构了一上午终于跑通",
        "新框架上手第一天", "上线前心跳加速", "代码 review 被夸了",
        "线上报警深夜响起", "终于把那个老接口下线了",
    ],
    "设计师": [
        "改稿改到第十八版", "甲方说要大气一点", "配色又调了一下午",
        "终于找到了对的那一像素", "原型被一句话推翻", "新字体上线试用",
        "灵感卡壳中", "做完图发现文件没保存",
    ],
    "教师": [
        "今天批了一沓作业", "学生问了让我愣住的问题", "公开课终于讲完",
        "班会聊了很久", "备课到深夜", "运动会累但开心",
        "家长会圆满结束", "收到学生写的卡片",
    ],
    "医生": [
        "今天一台手术六小时", "门诊看了五十个号", "夜班抢救成功",
        "查房走到腿软", "终于吃上一口热饭", "病人出院时说了谢谢",
        "学到新术式", "急诊一整夜没合眼",
    ],
    "律师": [
        "今天开庭三小时", "案子终于调解成功", "翻案卷翻到深夜",
        "当事人终于说了实话", "新法条研读中", "庭审被法官问住",
        "和解协议改了七稿", "证据链终于闭合",
    ],
    "记者": [
        "今天采访走了三个地方", "稿子被毙了重写", "现场记录到手抖",
        "采访对象沉默了很久", "终于找到关键信源", "发布会追问成功",
        "深夜赶稿中", "拍到想要的画面",
    ],
    "厨师": [
        "今天备料两百份", "新菜试做成功", "高峰期灶台连轴转",
        "食材到货验货", "老顾客又来了", "刀工练了半年",
        "汤熬了四个小时", "后厨默契满分",
    ],
    "摄影师": [
        "今天拍了落日", "镜头被雾气糊住", "等了两小时的光",
        "抓拍到想要的瞬间", "底片扫完很满意", "外景被风吹翻设备",
        "新滤镜试用", "暗房里待了一下午",
    ],
    "音乐人": [
        "新歌小样录完", "练琴练到指尖起茧", "现场返听出问题",
        "旋律突然冒出来", "编曲改了又改", "演出结束后台崩溃",
        "新乐器上手", "和乐手磨合到凌晨",
    ],
    "作家": [
        "今天写了三千字", "卡文卡了一周", "新角色突然活了",
        "编辑说这段要改", "灵感来自地铁上的对话", "改稿改到失语",
        "查资料查到走神", "终于写完这一章",
    ],
    "运动员": [
        "今天训练加量", "比赛前夜睡不好", "终于突破个人最好成绩",
        "恢复训练很累", "教练又加了一组", "客场作战胜利",
        "伤病恢复中", "热身时状态不错",
    ],
    "创业者": [
        "今天见投资人", "团队招到合适的人", "产品终于上线",
        "现金流又紧张了", "客户续约成功", "开了一天的会",
        "复盘到深夜", "拿到新订单",
    ],
    "公务员": [
        "今天材料改了五版", "窗口接待了一整天", "新政策开始执行",
        "下乡调研", "会议记录写到下班", "终于把流程跑通",
        "档案整理完", "值班一整夜",
    ],
    "护士": [
        "夜班交接顺利", "病人家属说了谢谢", "穿刺一针见血",
        "查房两万步", "终于坐下来喝水", "新护士带教中",
        "抢救配合默契", "交班前最后一刻处理完",
    ],
    "工程师": [
        "今天工地跑了一天", "图纸改了又改", "现场验收通过",
        "计算复核到深夜", "材料到场抽检", "方案终于过审",
        "设备调试成功", "协调会开了一下午",
    ],
    "研究员": [
        "今天实验跑了八组", "论文改稿第七版", "数据终于显著了",
        "仪器又出问题", "文献查到走神", "组会汇报顺利",
        "样本污染重做", "同行评议反馈来了",
    ],
    "学生": [
        "今天复习到熄灯", "考试终于考完", "选课抢到了热门课",
        "社团活动忙到飞起", "食堂新菜试吃", "论文ddl倒计时",
        "实习面试通过", "宿舍夜聊到两点",
    ],
    "退休者": [
        "今天公园打太极", "孙子来家里了", "菜场买了新鲜鱼",
        "老友聚了聚", "终于读完那本书", "花园里忙了一上午",
        "晨练遇到老邻居", "下棋赢了一盘",
    ],
    "艺术家": [
        "今天画室待了一天", "新作品配色定了", "布展累但值得",
        "灵感来自一场雨", "材料试错中", "展览开幕顺利",
        "评论收到反馈", "创作到忘我",
    ],
    "自由职业": [
        "今天接了新项目", "改稿改到怀疑人生", "终于交付了尾款",
        "咖啡馆办公一天", "时间自己安排真好", "客户难搞但搞定",
        "收入不稳定也自由", "在家办公的第十天",
    ],
}

TWEET_TAILS_BY_STYLE: Dict[str, List[str]] = {
    "正式": ["，特此记录。", "，持续关注中。", "，与诸位共勉。", "，望有所成。"],
    "口语": ["，真不错。", "，挺有意思。", "，给大家看看。", "，你们觉得呢？"],
    "幽默": ["，笑死我了。", "，绝绝子。", "，破防了家人们。", "，懂的都懂。"],
    "犀利": ["，别再自欺欺人。", "，话糙理不糙。", "，看透不说透。", "，现实就这么骨感。"],
    "温和": ["，愿你也有好心情。", "，慢慢来比较快。", "，日子还长。", "，且行且珍惜。"],
    "文艺": ["，岁月不语。", "，风过留痕。", "，皆是人间。", "，落笔成诗。"],
    "直率": ["，就这事。", "，说完了。", "，不废话。", "，干就完了。"],
}

# --------------------------------------------------------------------------- #
# 工具函数
# --------------------------------------------------------------------------- #

def build_active_windows(profession: str) -> List[bool]:
    """根据职业生成 24 槽 bool 数组。"""
    windows = [False] * 24
    for start, end in ACTIVE_WINDOWS_BY_PROFESSION.get(profession, [(9, 17)]):
        for hour in range(start, end + 1):
            if 0 <= hour < 24:
                windows[hour] = True
    return windows


def gen_display_name(culture: str, rng: random.Random) -> Tuple[str, str, bool]:
    """
    生成基于文化背景的中文名字。
    返回 (displayName, pinyin_initials, is_english)。
    海外华人有概率使用英文名 + 中文姓氏。
    """
    if culture == "海外华人" and rng.random() < 0.6:
        en_name, _gender = rng.choice(ENGLISH_NAMES)
        surname = rng.choice(SURNAMES_BY_CULTURE[culture])
        display = f"{en_name} {surname}"
        # 用户名基于英文名小写 + 姓氏首字母
        initials = en_name.lower() + SURNAME_INITIAL.get(surname, "x")
        return display, initials, True

    surname = rng.choice(SURNAMES_BY_CULTURE[culture])
    given_pool = GIVEN_BY_CULTURE[culture]
    # 单字名或双字名
    if rng.random() < 0.5:
        g1 = rng.choice(given_pool)
        display = surname + g1
        initials = SURNAME_INITIAL.get(surname, "x") + GIVEN_INITIAL.get(g1, "x")
    else:
        g1 = rng.choice(given_pool)
        g2 = rng.choice(given_pool)
        while g2 == g1:
            g2 = rng.choice(given_pool)
        display = surname + g1 + g2
        initials = (SURNAME_INITIAL.get(surname, "x")
                    + GIVEN_INITIAL.get(g1, "x")
                    + GIVEN_INITIAL.get(g2, "x"))
    return display, initials, False


# 全局已用 username 集合，确保唯一
_USED_USERNAMES: set = set()


def gen_username(initials: str, profession: str, is_english: bool,
                 rng: random.Random) -> str:
    """生成唯一 username：拼音首字母/英文名 + 职业 + 随机数。"""
    code = PROFESSION_CODE[profession]
    base_source = initials if is_english else initials
    for _ in range(50):
        suffix = rng.randint(1, 999)
        candidate = f"{base_source}_{code}_{suffix:03d}"
        if candidate not in _USED_USERNAMES:
            _USED_USERNAMES.add(candidate)
            return candidate
    # 极端兜底：用全局计数
    fallback = f"{base_source}_{code}_{len(_USED_USERNAMES):04d}"
    _USED_USERNAMES.add(fallback)
    return fallback


def gen_bio(profession: str, age_range: str, rng: random.Random) -> str:
    base = rng.choice(BIO_PROFESSION[profession])
    suffix_choices = [s for a, s in BIO_AGE_SUFFIX if a == age_range][0]
    suffix = rng.choice(suffix_choices)
    bio = base + suffix
    # 控制在 160 字符内
    return bio[:160]


def gen_worldview(profession: str, age_range: str, rng: random.Random) -> str:
    base = rng.choice(WORLDVIEW_PROFESSION[profession])
    modifier = rng.choice(WORLDVIEW_AGE_MODIFIER[age_range])
    return base + modifier


def gen_values(culture: str, profession: str, rng: random.Random) -> str:
    c_val = rng.choice(VALUES_CULTURE[culture])
    p_val = rng.choice(VALUES_PROFESSION[profession])
    return f"{c_val}；{p_val}"


def gen_catchphrase(rng: random.Random) -> List[str]:
    count = rng.randint(1, 2)
    return rng.sample(CATCHPHRASE_POOL, count)


def gen_emoji_preference(age_range: str, rng: random.Random) -> List[str]:
    pool = EMOJI_BY_AGE[age_range]
    if age_range == "18-24":
        k = rng.randint(4, 5)
    elif age_range == "25-34":
        k = rng.randint(3, 4)
    elif age_range == "35-44":
        k = rng.randint(2, 3)
    else:
        k = rng.randint(1, 2)
    k = min(k, len(pool))
    return rng.sample(pool, k)


def gen_typo_rate(age_range: str, rng: random.Random) -> float:
    if age_range == "18-24":
        lo, hi = 0.02, 0.05
    elif age_range == "25-34":
        lo, hi = 0.01, 0.03
    elif age_range == "35-44":
        lo, hi = 0.005, 0.02
    else:
        lo, hi = 0.0, 0.01
    return round(rng.uniform(lo, hi), 4)


def gen_historical_tweets(profession: str, language_style: str,
                          rng: random.Random) -> List[dict]:
    """生成 5-10 条历史推文，模板组合，避免单账号内重复。"""
    scenes = TWEET_SCENES[profession]
    tails = TWEET_TAILS_BY_STYLE[language_style]
    count = rng.randint(5, 10)
    used: set = set()
    tweets: List[dict] = []
    attempts = 0
    while len(tweets) < count and attempts < count * 30:
        attempts += 1
        scene = rng.choice(scenes)
        tail = rng.choice(tails)
        text = (scene + tail)[:200]
        if text in used:
            continue
        used.add(text)
        if rng.random() < 0.3:
            media_theme = rng.choice(MEDIA_THEMES)
        else:
            media_theme = None
        days_ago = rng.randint(1, 30)
        tweets.append({
            "text": text,
            "mediaTheme": media_theme,
            "daysAgo": days_ago,
        })
    # 按天数倒序排列（最近的在后）
    tweets.sort(key=lambda t: t["daysAgo"])
    return tweets


# 三元组去重保护：保证 (worldview, values, languageStyle) 全局无完全重复
_USED_TRIPLES: set = set()


def pick_unique_triple(profession: str, age_range: str, culture: str,
                       rng: random.Random) -> Tuple[str, str, str]:
    """选择 (worldview, values, languageStyle) 三元组，保证全局唯一。"""
    for _ in range(200):
        wv = gen_worldview(profession, age_range, rng)
        vals = gen_values(culture, profession, rng)
        style = rng.choice(LANGUAGE_STYLES)
        key = (wv, vals, style)
        if key not in _USED_TRIPLES:
            _USED_TRIPLES.add(key)
            return wv, vals, style
    # 兜底：通过微调 languageStyle 强制不同（理论上不会触达）
    wv = gen_worldview(profession, age_range, rng)
    vals = gen_values(culture, profession, rng)
    for style in LANGUAGE_STYLES:
        key = (wv, vals, style)
        if key not in _USED_TRIPLES:
            _USED_TRIPLES.add(key)
            return wv, vals, style
    # 最后兜底：追加随机标记
    style = rng.choice(LANGUAGE_STYLES)
    wv = wv + " "
    _USED_TRIPLES.add((wv, vals, style))
    return wv, vals, style


def build_persona(profession: str, age_range: str, culture: str,
                  slot_index: int) -> dict:
    """构建单条人设。每个 slot 使用独立 RNG 保证可复现。"""
    rng = random.Random(RANDOM_SEED + slot_index)
    display_name, initials, is_english = gen_display_name(culture, rng)
    username = gen_username(initials, profession, is_english, rng)
    bio = gen_bio(profession, age_range, rng)
    worldview, values, language_style = pick_unique_triple(
        profession, age_range, culture, rng
    )
    catchphrase = gen_catchphrase(rng)
    emoji_pref = gen_emoji_preference(age_range, rng)
    typo_rate = gen_typo_rate(age_range, rng)
    active_windows = build_active_windows(profession)
    historical_tweets = gen_historical_tweets(profession, language_style, rng)

    return {
        "id": str(uuid.uuid4()),
        "displayName": display_name,
        "username": username,
        "avatarSeed": username,
        "bio": bio,
        "profession": profession,
        "ageRange": age_range,
        "culturalBackground": culture,
        "worldview": worldview,
        "values": values,
        "languageStyle": language_style,
        "catchphrase": catchphrase,
        "emojiPreference": emoji_pref,
        "typoRate": typo_rate,
        "activeWindows": active_windows,
        "historicalTweets": historical_tweets,
    }


# --------------------------------------------------------------------------- #
# 矩阵抽样
# --------------------------------------------------------------------------- #

def build_matrix_slots() -> List[Tuple[str, str, str]]:
    """
    生成 220 个 (profession, age, culture) 槽位。
    每个职业固定 11 个 (20 x 11 = 220)。
    年龄下标 = (n + pi) % 4，使"短缺年龄"随职业旋转 -> 每个年龄合计 55 (>=40)。
    文化下标 = (n*5 + pi) % 6 (步长 5 与 6 互素)，使每个文化合计约 36-37 (>=30)。
    """
    slots: List[Tuple[str, str, str]] = []
    for pi, profession in enumerate(PROFESSIONS):
        for n in range(11):
            age_idx = (n + pi) % 4
            culture_idx = (n * 5 + pi) % 6
            slots.append((
                profession,
                AGE_RANGES[age_idx],
                CULTURAL_BACKGROUNDS[culture_idx],
            ))
    assert len(slots) == TOTAL_PERSONAS
    return slots


# --------------------------------------------------------------------------- #
# 输出
# --------------------------------------------------------------------------- #

def write_outputs(personas: List[dict]) -> None:
    PERSONAS_DIR.mkdir(parents=True, exist_ok=True)
    AVATARS_DIR.mkdir(parents=True, exist_ok=True)

    # 清理旧分片（避免残留）
    for old in PERSONAS_DIR.glob("personas_*.json"):
        old.unlink()
    index_old = PERSONAS_DIR / "index.json"
    if index_old.exists():
        index_old.unlink()

    # 写分片
    files: List[str] = []
    for i in range(0, len(personas), SHARD_SIZE):
        shard = personas[i:i + SHARD_SIZE]
        shard_no = i // SHARD_SIZE + 1
        fname = f"personas_{shard_no:03d}.json"
        (PERSONAS_DIR / fname).write_text(
            json.dumps(shard, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        files.append(fname)

    # 写 index.json
    index_obj = {
        "count": len(personas),
        "files": files,
        "accountIds": [p["id"] for p in personas],
    }
    (PERSONAS_DIR / "index.json").write_text(
        json.dumps(index_obj, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    # 写 avatars/index.txt（账号 ID 列表，每行一个）
    (AVATARS_DIR / "index.txt").write_text(
        "\n".join(p["id"] for p in personas) + "\n",
        encoding="utf-8",
    )


# --------------------------------------------------------------------------- #
# main
# --------------------------------------------------------------------------- #

def main() -> None:
    random.seed(RANDOM_SEED)
    slots = build_matrix_slots()
    personas: List[dict] = []
    for idx, (prof, age, culture) in enumerate(slots):
        personas.append(build_persona(prof, age, culture, idx))

    write_outputs(personas)

    # 简要统计
    print(f"生成人设总数: {len(personas)}")
    print(f"分片文件数: {(len(personas) + SHARD_SIZE - 1) // SHARD_SIZE}")
    print(f"输出目录: {PERSONAS_DIR}")
    print(f"头像索引: {AVATARS_DIR / 'index.txt'}")

    # 各维度分布
    prof_dist: Dict[str, int] = {}
    age_dist: Dict[str, int] = {}
    culture_dist: Dict[str, int] = {}
    for p in personas:
        prof_dist[p["profession"]] = prof_dist.get(p["profession"], 0) + 1
        age_dist[p["ageRange"]] = age_dist.get(p["ageRange"], 0) + 1
        culture_dist[p["culturalBackground"]] = \
            culture_dist.get(p["culturalBackground"], 0) + 1
    print("职业分布:", dict(sorted(prof_dist.items(),
                                  key=lambda x: PROFESSIONS.index(x[0]))))
    print("年龄段分布:", dict(sorted(age_dist.items(),
                                    key=lambda x: AGE_RANGES.index(x[0]))))
    print("文化背景分布:", dict(sorted(culture_dist.items(),
                                      key=lambda x: CULTURAL_BACKGROUNDS.index(x[0]))))


if __name__ == "__main__":
    main()
