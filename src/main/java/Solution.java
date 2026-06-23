class Solution {
	public int removeDuplicates(int[] nums) {
		int k = 1;
		if(nums.length <= 1) return k;
		for(int i = 0; i < nums.length; i++) {
			int j = i + 1;
			while(nums[i] == nums[j]) {
				j++;
			}
			int temp = nums[j];

			for(int j2 = j; j2 > i; j2--){
				nums[j2] = temp;
			}
			k++;
			if(j == nums.length - 1){
				break;
			}
		}
		return k;
	}

	public static void main(String[] args) {
		int[] nums = {1};

		Solution s = new Solution();
		int result = s.removeDuplicates(nums);

		System.out.print(result + ", ");
		System.out.print("nums = [");
		for(int i = 0; i < nums.length; i++ ){
			if(i == nums.length - 1) {
				System.out.println(nums[i] + "]");
				break;
			}
			System.out.print(nums[i] + ", ");
		}
	}
}
