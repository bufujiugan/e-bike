<template>
	<view class="content">
		<view  class="logo"  @click="onclickscan('')">
			<view class="ba_imga" ><image class="imgae_ico" src="../../static/scan.png"></image></view>
			<view class="ba_text"><text>{{$t('index_scan_text')}}</text></view>
		</view>
	<view  class="logo"  @click="onclicksprintf('')" >
		<view class="ba_imga" ><image class="imgae_ico" src="../../static/printf.png"></image></view>
		<view class="ba_text"><text>{{$t('index_printf_text')}}</text></view>
	</view>
	<view  class="logo" @click="onclickiduhf('')">
		<view class="ba_imga" ><image class="imgae_ico" src="../../static/fids.png"></image></view>
		<view class="ba_text"><text>超高频</text></view>
	</view>
	
	<view  class="logo"  @click="onclickidcard('')">
		<view class="ba_imga" ><image class="imgae_ico" src="../../static/usrfid.png"></image></view>
		<view class="ba_text"><text>{{$t('index_idcard_text')}}</text></view>
	</view>
	<!-- 	
		<view  class="logo"  @click="onclickedit('')">
			<view class="ba_imga" ><image class="imgae_ico" src="../../static/usrfid.png"></image></view>
			<view class="ba_text"><text>{{$t('index_idcard_text')}}</text></view>
		</view> -->
			

	</view>
</template>

<script>
	export default {
		data() {
			return {
			
			}
		},
		onLoad() {

		},
		methods: {
		onclickscan(e) {
			uni.navigateTo({
				//保留当前页面，跳转到应用内的某个页面
				url: '/pages/scan/scan'
			})
		
		
		},
		onclicksprintf(e) {
			uni.navigateTo({
				//保留当前页面，跳转到应用内的某个页面
				url: '/pages/printf/printf'
			})
		
		
		},
		onclickidcard(e) {
			uni.navigateTo({
				//保留当前页面，跳转到应用内的某个页面
				url: '/pages/Idcard/Idcard'
			})
		
		
		},onclickiduhf(e) {
			uni.navigateTo({
				//保留当前页面，跳转到应用内的某个页面
				url: '/pages/uhf/uhf'
			})
		
		
		},onclickedit(e) {
			uni.navigateTo({
				//保留当前页面，跳转到应用内的某个页面
				url: '/pages/labeledit/labeledit'
			})
		
		
		},

		}
	}
</script>

<style>
page{
	background-color: #18D3C9;
	
}
	
</style>
<style lang="scss">
	
	.content {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;

	}

	.logo {
		height: 168rpx;
		width: 500rpx;
		margin-top: 80rpx;
		margin-left: auto;
		margin-right: auto;
		border-radius: 10rpx;
		margin-bottom: 50rpx;
		background-color: #ffffff;
		display: flex;
		flex-direction:row;
		align-items: center;
		justify-content: center;
		.ba_imga{
			padding-left: 80rpx;
			padding-top: 30rpx;
			padding-bottom: 30rpx;
			width: 120rpx;
			height: 120rpx;

		}
		
	}
	.imgae_ico{
		width: 120rpx;
		height: 120rpx;
		flex: 1;
		
	}
	.ba_text{
			
				text-align: center;
				line-height: 125rpx;
			flex: 1;
			width: 120rpx;
			height: 120rpx;
		
			text{
				width: 169rpx;
				height: 56rpx;
				font-size: 60rpx;
				font-family: Source Han Sans CN;
				font-weight: 400;
				color: #18A29B;
				line-height: 31prpx;
				
			}
			
		}
	.text-area {
		display: flex;
		justify-content: center;
	}

	.title {
		font-size: 36rpx;
		color: #8f8f94;
	}
</style>
