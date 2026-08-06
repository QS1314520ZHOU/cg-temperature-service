package com.digixmed.cloud.icu.service.common;

import java.util.Arrays;
import java.util.Date;
import java.util.List;


public class MyConfig {
    public static final String YMDHMS = "yyyy-MM-dd HH:mm:ss";
    public static final String YMD = "yyyy-MM-dd";
    /* 16 */   public static final List<Integer> TIMEPOINTS = Arrays.asList(new Integer[]{Integer.valueOf(3), Integer.valueOf(7), Integer.valueOf(11), Integer.valueOf(15), Integer.valueOf(19), Integer.valueOf(23)});
    /* 17 */   public static final List<Integer> XYTIMEPOINTS = Arrays.asList(new Integer[]{Integer.valueOf(7), Integer.valueOf(15)});

    public static final String BEISIDE_CODE_NIAOLIANG = "param_niaoLiang";
    /* 20 */   public static final List<String> CODES_XUEYA = List.of("param_nibp_d", "param_ibp_d");
    public static final String BEISIDE_CODE_DABIAN = "param_daBianAmount";
    public static final String BEISIDE_CODE_DABIAN_CS = "param_daBianCiShu";
    /* 23 */   public static final List<String> BEISIDE_CODES_DABIAN = List.of("param_daBianAmount", "param_daBianCiShu");

    public static boolean STARTTIME = false;
    /* 26 */   public static int TIMPOINTASD = 7;

    /* 28 */   public static int TIMPOINTASD_CL = 15;

    /* 30 */   public static final String[] CODES = new String[]{"param_T", "param_HR", "param_resp", "param_nibp_d", "param_ibp_d"};

    public static final String WARDCODE = "2006";

    /* 34 */   public static final String[] XLCODES = new String[]{"param_xinLvLv"};

    public static final String MBCODE = "param_PR";

    /* 38 */   public static final String[] XYCODES = new String[]{"param_nibp_d", "param_ibp_d"};

    /* 40 */   public static final String[] DXCODES = new String[]{"param_niaoLiang", "param_daBianAmount"};

    /* 42 */   public static final String[] DBCODES = new String[]{"param_daBianAmount", "param_大便量g"};
    /* 43 */   public static final String[] NLCODES = new String[]{"param_niaoLiang"};

    /* 45 */   public static final String[] RLCODES = new String[]{"param_kouFu", "param_口服类型", "param_biSi", "param_鼻饲类型", "param_YaoYeti_in_hour", "param_YaoOther_in_hour", "param_YaoStomach_in_hour", "param_YaoShuXue_in_hour", "param_YaoTPN_in_hour", "param_输血入量（手录）", "param_输血类型", "param_别科带入液体", "param_带入液体类别"};

    /* 47 */   public static final String[] CLCODES = new String[]{"param_niaoLiang", "param_daBianAmount", "param_DabianXingzhuang", "param_weiyeliang", "param_weiyeXingzhuang", "param_outuwuliang", "param_outuwuXingzhuang", "param_yinLiuColor", "param_chaoLvLiang", "param_qitaChuLiang"};
    /* 48 */   public static final String[] CLCODES_OTHER = new String[]{"param_daBianAmount", "param_DabianXingzhuang", "param_weiyeliang", "param_weiyeXingzhuang", "param_outuwuliang", "param_outuwuXingzhuang", "param_yinLiuColor", "param_chaoLvLiang", "param_qitaChuLiang"};
    public static Date LASTEXECUTIONTIME_RuLiang;
    public static Date LASTEXECUTIONTIME_ChuLiang;
    public static Date LASTEXECUTIONTIME_DX;
    public static Date LASTEXECUTIONTIME_VITAL;
}
