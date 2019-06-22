package org.apache.ibatis.myalgorithm;

/**
 * Created by Administrator on 2019/6/21.
 */
public class CamelSort {
    public static void main(String[] args) {
        int[] input = new int[]{3, 1, 5, 2, 1, 6, 4, 7, 20, 18, 13, 14, 1, 1, 2, 1, 0, 2};
//        int[] input = new int[]{3, 10, 55, 2};
        //1 1 1 2 2 2
        for (int i = 0, j = 1, k = 2; k < input.length; i = i + 2, j = j + 2, k = k + 2) {
            putMaxValueInMid(input, i, j, k);
        }
        if (input.length % 2 == 0 && input[input.length - 1] < input[input.length - 2]) {
            exchange(input, input.length - 1, input.length - 2);
        }
        for (int i = 0; i < input.length; ++i) {
            System.out.print(input[i] + ",");
        }
    }

    public static void exchange(int[] input, int i, int j) {
        int temp = input[i];
        input[i] = input[j];
        input[j] = temp;
    }

    public static void putMaxValueInMid(int[] input, int low, int mid, int high) {
        if (input[low] > input[mid]) {
            exchange(input, low, mid);
        }
        if (input[high] > input[mid]) {
            exchange(input, mid, high);
        }
    }
}
