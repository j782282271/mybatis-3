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
        Solution s = new Solution();
        List<List<Integer>> sp = new ArrayList<List<Integer>>();
        sp.add(Arrays.asList(1, 5, 5, 1, 4, 0, 18));
        sp.add(Arrays.asList(3, 3, 6, 6, 4, 2, 32));
        int res = s.shoppingOffers(Arrays.asList(4, 3, 2, 9, 8, 8), sp, Arrays.asList(6, 5, 5, 6, 4, 1));
        System.out.println(res);
    }

    public int shoppingOffers(List<Integer> price, List<List<Integer>> special, List<Integer> needs) {
        int p = allToCode(price);
        List<GiftPack> giftPacks = toGiftPackList(special);
        int n = allToCode(needs);
        if (p == 0 || n == 0) {
            //全为0，直接返回0
            return 0;
        }
        giftPacks.addAll(price2GiftPack(price));
        return shoppingOffers(giftPacks, n, price.size(), 0);
    }

    public int shoppingOffers(List<GiftPack> giftPacks, int needsCode, int productSize, int start) {
        if (needsCode == 0) {
            return 0;
        }
        int val = initVal(giftPacks, productSize, needsCode);
        int needsCodeTemp = needsCode;
        for (int i = start; i < giftPacks.size(); ++i) {
            GiftPack gp = giftPacks.get(i);
            if (!isBiggerThanThreadjold(gp.code, needsCodeTemp, productSize)) {
                continue;
            }
            needsCodeTemp = needsCodeTemp - gp.code;
            val = Math.min(val, gp.price + shoppingOffers(giftPacks, needsCodeTemp, productSize, i));
            needsCodeTemp = needsCode;
        }
        return val;
    }

    private int initVal(List<GiftPack> giftPacks, int productSize, int needsCode) {
        int res = 0;
        int i = productSize;
        while (i >= 1) {
            GiftPack gp = giftPacks.get(giftPacks.size() - productSize - 1 + i);
            int tm = needsCode % 10;
            res = res + tm * gp.price;
            needsCode = needsCode / 10;
            --i;
        }
        return res;
    }

    private static class GiftPack {
        int code;
        int price;
    }

    boolean isBiggerThanThreadjold(int test, int threadhold, int productSize) {
        int i = 1;
        while (i <= productSize) {
            int tm = test % 10;
            int thm = threadhold % 10;
            if (tm > thm) {
                return false;
            }
            test = test / 10;
            threadhold = threadhold / 10;
            ++i;
        }
        return true;
    }

    static GiftPack fromList(List<Integer> list) {
        GiftPack res = new GiftPack();
        res.code = toCode(list);
        res.price = list.get(list.size() - 1);
        return res;
    }

    static List<GiftPack> toGiftPackList(List<List<Integer>> list) {
        List<GiftPack> res = new ArrayList<GiftPack>();
        for (List<Integer> l : list) {
            res.add(fromList(l));
        }
        return res;
    }

    static Integer toCode(List<Integer> list) {
        int res = 0;
        for (int i = 0; i < list.size() - 1; ++i) {
            res = (int) (res + list.get(i) * Math.pow(10, list.size() - 2 - i));
        }
        return res;
    }

    static Integer allToCode(List<Integer> list) {
        int res = 0;
        for (int i = 0; i < list.size(); ++i) {
            res = (int) (res + list.get(i) * Math.pow(10, list.size() - 1 - i));
        }
        return res;
    }

    static List<GiftPack> price2GiftPack(List<Integer> price) {
        List<GiftPack> res = new ArrayList<GiftPack>();
        for (int i = 0; i < price.size(); ++i) {
            GiftPack g = new GiftPack();
            g.code = (int) Math.pow(10, price.size() - 1 - i);
            g.price = price.get(i);
            res.add(g);
        }
        return res;
    }
}
