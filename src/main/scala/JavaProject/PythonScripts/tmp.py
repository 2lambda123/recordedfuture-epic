import pymongo 
import sys 
import os 
from pymongo import MongoClient 
from moveBatch import moveBatch
rString = moveBatch([0.19534213073772377,0.631323209736077,0.4780361644453206,0.7009721911333314,0.29793070281733813,0.16642835861147343,0.7629656941475562,0.5275516453079332,0.6999344666861388,0.68707821177527,0.4595500372955861,0.5365235742847108,0.1149508423783645,0.4575271041026744,0.28655309195164036,0.5164091814070939,0.23210626899838616,0.48049806622387037,0.44379869808165706,0.36635078947827493,0.23570916059870883,0.42049528676993597,0.32342156862438165,0.22141263073766304,0.43838746721727706,0.6968466722561508,0.2459536966925786,0.09213123798760792,0.3648509480211458,0.5844591123187897,0.39033906600224233,0.05576879874774798,0.7358221135924671,0.6958427357326444,0.7213561396366358,0.15083410628855087,0.5587393340726606,0.2123263652908377,0.6581948463471227,0.2110198514892162,0.5674006125182294,0.26069544671224765,0.6560521454926546,0.5588558472574797,0.43947510838074966,0.4775167440150825,0.617981014894197,0.4165915069366386,0.31009237870140816,0.48055164821976704,0.4148218789154807,0.24632921131462415,0.3488423646550576,0.13205587853317613,0.49113330610772143,0.341540898726999,0.6375231525504217,0.6525255924582306,0.5024103770032565,0.37552248273183575,0.147798082118894,0.4061619215054355,0.21616044317204852,0.6972898470633215,0.12440265080188129,0.2513739001144979,0.12544629146920183,0.6385899042307392,0.3682254921501348,0.15139667391405698,0.5118657173156481,0.7492791651783423,0.5035965986414929,0.5742772290302229,0.17814366658924297,0.18473693113205125,0.3998664962533556,0.4544407188679055,0.1368186303472705,0.04932732832267217,0.730880095851652,0.4537164521068271,0.7685806177270058,0.1211045883381231,0.5355303555648903,0.47895843443956554,0.7302004167825371,0.7891738993803956,0.4128783660843466,0.20335628719319687,0.07158729553749343,0.11654114587180553,0.41971735620582784,0.06261545363920074,0.4617566418441903,0.5903288457996647,0.6938026180251368,0.7561877543777435,0.12453141681356383,0.7978814638565429,0.13182182433615908,0.46181473467769873,0.20364903301432136,0.22167287881315423,0.24572655677710997,0.1650604971106986,0.26770705039606213,0.4100573194550228,0.44860442661894595,0.5618080964347162,0.42389487248321156,0.2888887240975122,0.179620248851055,0.64910694923394,0.690378144432572,0.3864479221492161,0.19665769617311302,0.7723065416925268,0.1263948524526809,0.5195948632356641,0.07456574627823576,0.5722472431386071,0.4624372405605097,0.06480642171575124,0.1094147796055922,0.5123778248363058,0.3532881197502714,0.4375417560556578,0.7488610495159744,0.10419958915539218,0.7773753433692225,0.6254043662693032,0.12191589035616712,0.7900596936912505,0.2868364118982625,0.43773810510457145,0.2863066663232704,0.18514335305364837,0.4413617056006176,0.07342686609747628,0.1356173571982966,0.4192919801627263,0.30217846914696034,0.3524749895226865,0.0998945280768847,0.42679560234640235,0.29998016709077635,0.786129274906976,0.722767208217583,0.09634847882649267,0.6012777972832322,0.6332552833824896,0.05002922100693452,0.058195086774011195,0.23099087705690702,0.720250243844639,0.34893521334642097,0.48427570049644464,0.790728731048285,0.17570742354296554,0.22913478205014126,0.5230439996809478,0.6519448018675948,0.21499370024384812,0.6929190697747555,0.7311245891682858,0.2689295544567224,0.20468725515105246,0.31078668298400347,0.39624855559475913,0.4239685856886467,0.20345830475196736,0.30462228161583316,0.1255339836195244,0.2195446763026091,0.5289252607499838,0.40899535408233567,0.06884541672235678,0.4845733393838062,0.655384431039325,0.10906411477105893,0.690264616778862,0.3515333979281544,0.15270703328219393,0.4455830726814618,0.35380107888146817,0.7392477904486215,0.46156377995431075,0.7251859159835814,0.37887985257331336,0.41475043255346455,0.0776105731920228,0.0472491556677358,0.04688419684482692,0.5136566187497724,0.4711192785903012,0.5962410063734258,0.49566841903002834,0.7756312148607235,0.5862572848813832,0.4327372299668154,0.18186698217871722,0.7610786681220943,0.715346029252821,0.2773099775564156,0.16068912327534302,0.11200602525147285,0.166797821832126,0.24912815450192127,0.6531925460555006,0.5820606413703765,0.40645320238697713,0.5207441393329566,0.5495654271845409,0.24412540790741866,0.6284839253600739,0.11608033019479191,0.7974896493527179,0.7442398618732176,0.06136461649669711,0.5000169537094944,0.1532463485564426,0.6392288371663231,0.5081877965826632,0.3382431898689475,0.5307269346222554,0.637326019690095,0.4327199331226349,0.047519614198150095,0.4975210404009436,0.6188958631287419,0.4165457767743328,0.34628873726355247,0.6393131082847353,0.4543209512849766,0.5229482231464636,0.32137203300406203,0.35467520581045786,0.37062137860760114,0.21345680771967634,0.6007676845431602,0.5595053138386653,0.794742435505281,0.3701730955045591,0.3509490391264932,0.4585359731601467,0.7149999008475664,0.5249712458910551,0.20517893340557392,0.4570536571151792,0.4436147131033088,0.22024208474817175,0.08150833610979524,0.5886920664560423,0.6907028884230043,0.7513799981267886,0.5625126635671324,0.31393376050056165,0.623407728401415,0.14277374116501385,0.2736557744103605,0.7041537855113951,0.5994622194659857,0.08473867883397646,0.4297593883885552,0.7699508393144194,0.7465141571256826,0.2564053122798191,0.7752044074569988,0.5356736349201504,0.05668707869018896,0.7372081163860598,0.08409747548094881,0.09200401210839071,0.17643091071573924,0.4253127065169371,0.08948494222246728,0.22674430696774184,0.3740949392859536,0.4947981885397741,0.5891929835977068,0.6907443565925846,0.06019097660190609,0.4289063812822965,0.2584160275807771,0.4735949274367932,0.515997354213471,0.6117548049846278,0.68534567068767,0.3413258514869789,0.6260403420668765,0.6444625825507702,0.3366029291633237,0.7412595011861624,0.4299771061927249,0.25997752960970166,0.07467380738678664,0.7206745324787004,0.627051120511029,0.24596206323883563,0.4791940505995357,0.1482215926150936,0.6515863522422028,0.7774928482732675,0.5001082411219605,0.21893624894509955,0.43959485574957924,0.3762977578015372,0.3662085078105183,0.7477425344446188,0.6312133613850133,0.2555359520202515,0.5671518595629483,0.35335761627567586,0.1713869029806553,0.5871424855376344,0.1762925651730417,0.4336710556601072,0.6390152224817879,0.4896933777430508,0.3779639401974443,0.08199120713235108,0.5871947493832008,0.07428974376938302,0.4621678362288034,0.23276888640213456,0.7592089360141622,0.15294759072280795,0.5297297509401733,0.40335302412419516,0.13607830568001733,0.46171364399206183,0.6440228548868705,0.7039932007770588,0.20845876370875538,0.07832991604932105,0.692606976281116,0.5676977087884576,0.48731939498299903,0.13174055325395617,0.2342642367183937,0.5428384704075013,0.33469289390651946,0.3175404009269641,0.5242075311083061,0.21597676447303238,0.30875995330026385,0.6114924645122201,0.20339689454609378,0.6854760685984975,0.7294662386459586,0.5442980995831066,0.624183587044777,0.4492013685427729,0.5355398616314103,0.7887325465390326,0.2747305108284289,0.7435255089779991,0.05560626154408055,0.7779569001983698,0.7300677760153341,0.09265406538507481,0.06184296351755403,0.68082029019162,0.5239235775080878,0.5655756892721157,0.6457309930528727,0.14428697153861514,0.32260750502952595,0.48662861912589217,0.7984451126725289,0.0731694563922427,0.18511344601016433,0.36176162037163395,0.05202576291051042,0.4376681097831966,0.6323274935794084,0.16080525320030892,0.34213335154121893,0.6585325854098979,0.7349794519264353,0.13992436020473065,0.5597898199803184,0.4068016831696223,0.2806707230696437,0.34906514908434794,0.7864953218064271,0.41325058407440296,0.05066314542426864,0.39590335924330533,0.2991503710958493,0.2365633749948175,0.43101992269766276,0.6607637886480434,0.7358393006568441,0.7059180242496793,0.11878618110989414,0.4540586835014817,0.6488562473251915,0.5498529500135899,0.05714444560477239,0.367201662306781,0.14020957576411397,0.4403941396692881,0.18069668927590365,0.5249600873954096,0.7739165484110648,0.2765272477842563,0.5481750562753169,0.47542151279431577,0.18892853396481968,0.12245486146921614,0.1205434577472998,0.2677334413571021,0.3985472588599174,0.3321625801580007,0.761788325125396,0.7855981862398309,0.48779142550471966,0.19250182674360394,0.6501395985527868,0.31956402237279624,0.6161982834169775,0.1709993238876094,0.3227198771380554,0.6861797708719392,0.5820495893861547,0.0865677382202229,0.45439513876987114,0.23125325516339745,0.04709613439514537,0.059529865627189205,0.5336451220744283,0.7462898594160452,0.3667421125430411,0.6774266710119129,0.21997578678886054,0.34850937289371364,0.6635244965052959,0.3340726690538024,0.5957349045804943,0.2378837898461611,0.10366736492884543,0.5405945794936816,0.12182555423768415,0.053685290487832105,0.4261681465027959,0.2613886678996111,0.2500831819225374,0.20902593456165386,0.2702553808118513,0.44268315233597644,0.18717076828442702,0.5103293428965607,0.3011435144680682,0.10668589081922031,0.7866439956873835,0.3267488833338529,0.7128746402172536,0.2085805578996257,0.06175016758737262,0.12271427150179082,0.05646231213286579,0.6989448886077191,0.47346862226415587,0.0435255059691958,0.28523576986044563,0.7146867497524465,0.7479565173560208,0.2777207675166925,0.46054595987279434,0.770479039625655,0.06036470125300797,0.7122487186637648,0.5110621405903891,0.33199305307299654,0.38406991518568745,0.6221145381105778,0.6066082536429903,0.10029268008769043,0.5645457658185276,0.2153962080635069,0.1770951197631716,0.4006448748195397,0.42341630532643404,0.6020552249482919,0.5829192498729674,0.2431316545824309,0.10954159844307532,0.7391987836530642,0.17114186326029268,0.5069617288478234,0.07546631319991193,0.19519012070901065,0.11712814955771922,0.46308333428093784,0.5552081217713665,0.41707452536754863,0.7665375540331288,0.20046188207951587],0.0)
print str(rString)
