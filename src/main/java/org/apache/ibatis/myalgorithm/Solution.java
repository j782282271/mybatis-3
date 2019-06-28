package org.apache.ibatis.myalgorithm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Created by Administrator on 2019/6/28.
 * https://leetcode-cn.com/problems/shopping-offers/
 */
public class Solution {
    public static void main(String[] args) {
        int res = shoppingOffers(Arrays.asList(2, 5), Arrays.asList(Arrays.asList(3, 0, 5), Arrays.asList(1, 2, 10)), Arrays.asList(3, 2));
        System.out.println(res);
    }

    public static int shoppingOffers(List<Integer> price, List<List<Integer>> special, List<Integer> needs) {
        if (isAll0(needs)) {
            //全为0，直接返回0，递归出口
            return 0;
        }
        int oneNot0Index = oneNot0Index(needs);
        if (oneNot0Index != -1) {
            //只有一个元素不为0则直接计算结果，不需要使用大礼包，也是递归出口
            return needs.get(oneNot0Index) * price.get(oneNot0Index);
        }

        special = reform(special);
        int minRest = Integer.MAX_VALUE;
        int val = 0;
        for (int i = 0; i < special.size(); ++i) {
            List<Integer> needTemp = new ArrayList<Integer>(needs);
            int valTemp = calPrice(special.get(i), needTemp);
            if (valTemp == -1) {
                continue;
            }
            int rest = getPrice(price, needTemp);
            if (minRest > rest) {
                minRest = rest;
                val = valTemp;
            }
        }

        val = val + minRest;
        return val;
    }

    public static List<List<Integer>> deepCopy(List<List<Integer>> special) {
        List<List<Integer>> res = new ArrayList<List<Integer>>(special.size());
        for (int i = 0; i < special.size(); ++i) {
            List<Integer> list = new ArrayList<Integer>();
            list.addAll(special.get(i));
            res.add(list);
        }
        return res;
    }

    public static int add(List<List<Integer>> res, int start, List<List<Integer>> special) {
        int size = res.size();
        if (start >= size) {
            return -1;
        }
        for (int i = start; i < size; ++i) {
            label:
            for (int j = 0; j < special.size(); ++j) {
                List<Integer> list = new ArrayList<Integer>();
                for (int k = 0; k < res.get(i).size(); ++k) {
                    int val = res.get(i).get(k) + special.get(j).get(k);
                    if (k != res.get(i).size() - 1 && val > 6) {
                        break label;
                    }
                    list.add(val);
                }
                res.add(list);
            }
        }
        return res.size();
    }

    public static List<List<Integer>> reform(List<List<Integer>> special) {
        List<List<Integer>> res = deepCopy(special);
        int i = 0;
        while (i != -1) {
            i = add(res, i, special);
        }
        return res;
    }


    public static int getPrice(List<Integer> price, List<Integer> needs) {
        int val = 0;
        for (int i = 0; i < needs.size(); ++i) {
            val = val + getIndexPrice(price, i, needs.get(i));
        }
        return val;
    }

    public static int getIndexPrice(List<Integer> price, int index, int size) {
        return price.get(index) * size;
    }

    //只有一个元素不为0则返回该元素的位置
    private static int oneNot0Index(List<Integer> needs) {
        int not0Num = 0;
        int index = -1;
        for (Integer n : needs) {
            if (0 != n) {
                not0Num++;
                index = n;
            }
            if (not0Num > 1) {
                return -1;
            }
        }
        return not0Num == 1 ? index : -1;
    }

    private static boolean isAll0(List<Integer> needs) {
        for (Integer n : needs) {
            if (0 != n) {
                return false;
            }
        }
        return true;
    }

    private static int calPrice(List<Integer> giftPack, List<Integer> needs) {
        for (int i = 0; i < giftPack.size() - 1; ++i) {
            if (needs.get(i) < giftPack.get(i)) {
                return -1;
            }
        }
        for (int i = 0; i < giftPack.size() - 1; ++i) {
            needs.set(i, needs.get(i) - giftPack.get(i));
        }
        return giftPack.get(giftPack.size() - 1);
    }
}
