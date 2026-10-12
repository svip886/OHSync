package io.github.bqj6666.ohsync.core

import androidx.health.connect.client.records.ExerciseSessionRecord

/**
 * OPPO 健康的 sport_mode → Health Connect 的 EXERCISE_TYPE_*。
 *
 * 左侧取值来自 `com.heytap.databaseengine.type.SportMode`（对 OPPO 健康 6.9.x 的 dex
 * 反编译确认），右侧一律引用 HC 的官方常量名、不写字面量 —— 这批数值和直觉差很远
 * （RUNNING=56、DANCING=16、STRENGTH_TRAINING=70），抄错一个数字就会往 Health Connect
 * 里写进一条类型错误的运动。
 *
 * HC 的类型集比 OPPO 小得多（OPPO 150+ 种，HC 60 余种）。只有语义明确对得上的才映射，
 * 其余的（棋牌、乘车、久站、自定义运动、分类聚合值等）统一落到 OTHER_WORKOUT，
 * 不做"看起来差不多"的硬凑。
 */
internal object ExerciseTypeMap {

    fun toHc(sportMode: Int): Int = when (sportMode) {
        // —— 走 / 跑 ——
        WALK, AUTO_MODE_WALK, INDOOR_FITNESS_WALK, WALK_THE_DOG, ON_FOOT ->
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING

        RUN, AUTO_MODE_RUN, MARATHON, OUTDOOR_PHYSICAL_RUN, OUTDOOR_5KM_RELAX_RUN,
        OUTDOOR_FAT_REDUCE_RUN, CROSS_COUNTRY, RUNNING_COURSE_OUTDOOR, PLAYGROUND_RUN ->
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING

        INDOOR_RUN, INDOOR_PHYSICAL_RUN, INDOOR_5KM_RELAX_RUN, INDOOR_FAT_REDUCE_RUN,
        TREADMILL_RUN, RUNNING_COURSE_INDOOR ->
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL

        // —— 骑行 ——
        RIDE -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
        INDOOR_MOTION_BIKE -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY

        // —— 游泳 ——
        SWIM -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
        OPEN_WATER_SWIM -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER

        // —— 攀爬 ——
        MOUNTAIN_CLIMBING, CLIMB -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
        ROCK_CLIMBING -> ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING

        // —— 球类 ——
        BADMINTON -> ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON
        PINGPONG -> ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS
        BASKETBALL -> ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL
        FOOTBALL -> ExerciseSessionRecord.EXERCISE_TYPE_SOCCER
        VOLLEYBALL -> ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL
        TENNIS -> ExerciseSessionRecord.EXERCISE_TYPE_TENNIS
        BASEBALL -> ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL
        SOFTBALL -> ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL
        AMERICAN_FOOTBALL -> ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN
        CRICKET -> ExerciseSessionRecord.EXERCISE_TYPE_CRICKET
        GOLF -> ExerciseSessionRecord.EXERCISE_TYPE_GOLF

        // —— 器械 / 有氧 ——
        ELLIPTICAL_MACHINE -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL
        ROWING_MACHINE -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE
        ROWING -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING
        STEPPER, STAIR_CLIMBER, TREADMILL_CLIMB, CLIMBER ->
            ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE
        AEROBICS -> ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS

        // —— 瑜伽 / 冥想 ——
        YOGA, ANUSARA, ASHTANGA_YOGA, IYENGAR_YOGA, FLY_YOGA, HATHA_YOGA, AERIAL_YOGA,
        PHYSIOTHERAPY_YOGA, FLOW_YOGA, VIPASSANA_FLOW_YOGA, YIN_YOGA, PREGNANCY_YOGA ->
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA
        MEDITATION -> ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING
        PILATES -> ExerciseSessionRecord.EXERCISE_TYPE_PILATES

        // —— 舞蹈 ——
        BALLET, DISCO, BELLY_DANCE, SQUARE_DANCE, WALTZ, STREET_DANCE, JAZZ, LATIN_DANCE,
        TANGO, TAP_DANCE -> ExerciseSessionRecord.EXERCISE_TYPE_DANCING

        // —— 力量 / 健身 ——
        FITNESS, FREE_TRAINING, EXPLOSIVE_TRAINING, BACK_TRAINING, HORIZONTAL_BAR,
        ABDOMINAL_TRAINING, CORE_TRAINING, SHOULDER_TRAINING, NECK_TRAINING,
        STRENGTH_TRAINING, FACE_TRAINING, AGILITY_TRAINING, BALANCE_TRAINING,
        FLEXIBILITY_TRAINING, UPPER_LIMB_TRAINING, PARALLEL_BARS, HIP_TRAINING,
        LOWER_LIMB_TRAINING, CHEST_TRAINING, WAIST_TRAINING, PRO_STRENGTH_TRAINING,
        GYM_STRENGTH_TRAINING -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING

        // —— 格斗 / 体操 ——
        BOXING -> ExerciseSessionRecord.EXERCISE_TYPE_BOXING
        FENCING -> ExerciseSessionRecord.EXERCISE_TYPE_FENCING
        GYMNASTICS -> ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS
        JUDO, TAEKWONDO, MARTIAL_ARTS, TAI_CHI ->
            ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS

        // —— 冰雪 ——
        SKI -> ExerciseSessionRecord.EXERCISE_TYPE_SKIING
        SKATE -> ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING
        ROLLER_SKATING -> ExerciseSessionRecord.EXERCISE_TYPE_SKATING

        // —— 水上 / 极限 ——
        SURF -> ExerciseSessionRecord.EXERCISE_TYPE_SURFING
        SAILBOAT -> ExerciseSessionRecord.EXERCISE_TYPE_SAILING
        KAYAKING -> ExerciseSessionRecord.EXERCISE_TYPE_PADDLING
        WATER_POLO -> ExerciseSessionRecord.EXERCISE_TYPE_WATER_POLO
        DIVING -> ExerciseSessionRecord.EXERCISE_TYPE_SCUBA_DIVING
        FRISBEE -> ExerciseSessionRecord.EXERCISE_TYPE_FRISBEE_DISC

        // —— 其余（棋牌、乘车、久站、自定义运动、分类聚合值…）无对应类型 ——
        else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
    }

    // ---- OPPO com.heytap.databaseengine.type.SportMode 的实测取值 ----
    private const val WALK = 1
    private const val RUN = 2
    private const val RIDE = 3
    private const val CLIMB = 5
    private const val SWIM = 7
    private const val GOLF = 8
    private const val FITNESS = 9
    private const val INDOOR_RUN = 10
    private const val YOGA = 12
    private const val OUTDOOR_PHYSICAL_RUN = 13
    private const val INDOOR_PHYSICAL_RUN = 14
    private const val OUTDOOR_5KM_RELAX_RUN = 15
    private const val INDOOR_5KM_RELAX_RUN = 16
    private const val OUTDOOR_FAT_REDUCE_RUN = 17
    private const val INDOOR_FAT_REDUCE_RUN = 18
    private const val INDOOR_FITNESS_WALK = 19
    private const val TREADMILL_RUN = 21
    private const val MARATHON = 22
    private const val BADMINTON = 31
    private const val ELLIPTICAL_MACHINE = 32
    private const val ROWING_MACHINE = 33
    private const val INDOOR_MOTION_BIKE = 34
    private const val FREE_TRAINING = 35
    private const val MOUNTAIN_CLIMBING = 36
    private const val CROSS_COUNTRY = 37
    private const val AUTO_MODE_RUN = 40
    private const val AUTO_MODE_WALK = 41
    private const val RUNNING_COURSE_INDOOR = 43
    private const val RUNNING_COURSE_OUTDOOR = 44
    private const val EXPLOSIVE_TRAINING = 201
    private const val BACK_TRAINING = 202
    private const val HORIZONTAL_BAR = 203
    private const val CLIMBER = 204
    private const val ABDOMINAL_TRAINING = 205
    private const val CORE_TRAINING = 206
    private const val FENCING = 207
    private const val SHOULDER_TRAINING = 208
    private const val AEROBICS = 209
    private const val NECK_TRAINING = 210
    private const val STRENGTH_TRAINING = 211
    private const val FACE_TRAINING = 212
    private const val AGILITY_TRAINING = 213
    private const val BALANCE_TRAINING = 214
    private const val BOXING = 215
    private const val JUDO = 216
    private const val FLEXIBILITY_TRAINING = 217
    private const val UPPER_LIMB_TRAINING = 218
    private const val PARALLEL_BARS = 221
    private const val STEPPER = 222
    private const val TAEKWONDO = 223
    private const val TAI_CHI = 224
    private const val GYMNASTICS = 225
    private const val HIP_TRAINING = 226
    private const val MARTIAL_ARTS = 227
    private const val LOWER_LIMB_TRAINING = 228
    private const val CHEST_TRAINING = 229
    private const val WAIST_TRAINING = 230
    private const val PRO_STRENGTH_TRAINING = 231
    private const val STAIR_CLIMBER = 232
    private const val TREADMILL_CLIMB = 234
    private const val GYM_STRENGTH_TRAINING = 290
    private const val ANUSARA = 301
    private const val ASHTANGA_YOGA = 302
    private const val IYENGAR_YOGA = 303
    private const val FLY_YOGA = 304
    private const val HATHA_YOGA = 305
    private const val AERIAL_YOGA = 306
    private const val PHYSIOTHERAPY_YOGA = 307
    private const val FLOW_YOGA = 308
    private const val MEDITATION = 309
    private const val VIPASSANA_FLOW_YOGA = 310
    private const val PILATES = 311
    private const val YIN_YOGA = 312
    private const val PREGNANCY_YOGA = 313
    private const val BALLET = 401
    private const val DISCO = 402
    private const val BELLY_DANCE = 403
    private const val SQUARE_DANCE = 404
    private const val WALTZ = 405
    private const val STREET_DANCE = 406
    private const val JAZZ = 407
    private const val LATIN_DANCE = 408
    private const val TANGO = 409
    private const val TAP_DANCE = 410
    private const val ROLLER_SKATING = 503
    private const val ROCK_CLIMBING = 504
    private const val ON_FOOT = 506
    private const val CRICKET = 601
    private const val BASEBALL = 602
    private const val AMERICAN_FOOTBALL = 603
    private const val BASKETBALL = 604
    private const val SOFTBALL = 605
    private const val VOLLEYBALL = 607
    private const val PINGPONG = 608
    private const val TENNIS = 610
    private const val FOOTBALL = 611
    private const val SKATE = 704
    private const val SKI = 705
    private const val SURF = 801
    private const val SAILBOAT = 802
    private const val KAYAKING = 804
    private const val ROWING = 806
    private const val WATER_POLO = 807
    private const val DIVING = 808
    private const val OPEN_WATER_SWIM = 809
    private const val FRISBEE = 904
    private const val WALK_THE_DOG = 905
    private const val PLAYGROUND_RUN = 909
}
