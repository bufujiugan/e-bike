<template>
	<view class="content">
		<view class="input_data"><input v-model="mobile"   class="input_data_scan"/></view>
		<view class="input_data_list">
		
			 <textarea :value="strings">

			    　　</textarea>
							   <!--  <text class="list_txt"  v-for="item in listarr">{{item.data}}</text> -->
			</view>
		<view class="buttx">
			<!-- 			<view class="print_but"><image class="butimg" src="../../static/printf.png"></image><text>打印</text></view> -->
			<view class=" print_but" @click="scan()">
				<image class="butimg" src="../../static/scan.png"></image><text>{{$t('index_scan_text')}}</text>
					</view>
			<view class="print_but" @click="scanclear()">
				<image class="butimg" src="../../static/16573c8afec57ca.png"></image><text>{{$t('page_scan_button_clear')}}</text>
			</view>
		</view>
	</view>
</template>

<script>
	const Qs601Module = uni.requireNativePlugin("Qs601Module");
	var main, receiver, filter, srcimg, userimg;
	var _codeQueryTag = false;
	export default {
		data() {
			return {
				mobile: "",

			//	listarr: [{data:"333"},{data:"333"}],
				strings:"",

			}
		},
		onHide: function() {
		
			this.stopReceiver();
		},
		
		
		onCallBack : function() {
		
			this.stopReceiver();
		},
		destroyed: function() {


		
			/*页面退出时一定要卸载监听,否则下次进来时会重复，造成扫一次出2个以上的结果*/
			this.stopReceiver();
		},
		
		onShow: function() {
			uni.setNavigationBarTitle({
             title:this.$t('page_scan_title')
         });
			/*页面退出时一定要卸载监听,否则下次进来时会重复，造成扫一次出2个以上的结果*/
			this.initScan()
			this.startReceiver();
			Qs601Module.init("",true,52);;
			// Qs5502Module.initidrd("ok", result => {

			// 	console.log('返回的数据用户点击确定onShow' + result)
			// })
		},
		methods: {
			initScan() { //扫码
				console.log("kaioshiss：")
				let _this = this;
				main = plus.android.runtimeMainActivity(); //获取activity  
				var IntentFilter = plus.android.importClass('android.content.IntentFilter');
				filter = new IntentFilter();
				filter.addAction("com.qs.scancode"); // 换你的广播动作  
				receiver = plus.android.implements('io.dcloud.feature.internal.reflect.BroadcastReceiver', {
					onReceive: function(context, intent) {
						plus.android.importClass(intent);
						let scandata = intent.getStringExtra("data"); //
						//	if(scandata!=""){
						_this.SCANCode(scandata); //扫码
						scandata = null;
						//}
					}
				});
			},
			SCANCode: function(code) {
				
				console.error("CANDATA：" + code)
				this.mobile = code;
				// var scarobj = {
				// 	data: this.mobile
				// }
				this.strings+=this.mobile;
			//	this.listarr.push(scarobj);
				// if(code==null)return false;
				// //防重复  
				// if (_codeQueryTag) return false;
				// _codeQueryTag = true;
				// setTimeout(function() {
				// 	_codeQueryTag = false;
				// }, 150);
				// var id = code

				// console.log('CANDATA:', id)
				// code="";

			},
			scan() {
				
				Qs601Module.scan("ok", result => {

				})

			},
			scanclear: function() {
	this.strings="";
			//this.listarr.splice(0);
				
				
			},
			
			startReceiver() {

				main.registerReceiver(receiver, filter);
			},
			handleError(evt) {
				const index = evt.target.dataset.index;
				this.imageList[index].src = this.correctUrl;
			},
			stopReceiver() {
				console.log('用户点击取消')
				main.unregisterReceiver(receiver);
				Qs601Module.closeDevice("", result => {


				})
			},
		}
	}
</script>

<style lang="scss">
	
	.content {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		width: 100%;

		.input_data {
			width: 100%;
			padding: 10rpx 5rpx;




		}

		.buttx {
			width: 100%;
			display: flex;
			flex-direction: row;
			align-items: center;
			justify-content: center;

			.print_but {

				margin-top: 50rpx;
				margin-left: auto;
				margin-right: auto;
				border-radius: 10rpx;
				display: flex;
				flex-direction: row;
				align-items: center;
				justify-content: center;
				width: 280rpx;
				height: 100rpx;
				background-color: #13C2B9;

				text {
					font-size: 36rpx;
					font-family: Source Han Sans CN;
					font-weight: 400rpx;
					color: #FFFFFF;

				}

			}

		}

		.input_data_scan {
			width: 100%;
			height: 150rpx;
			text-align: center;
			line-height: 150rpx;
			box-shadow: 2px 3px 6px 0px rgba(0, 14, 24, 0.09);
			border-radius: 10rpx;
			background-color: #ffff2;
		}


		.input_data_list {
			padding: 10rpx 10rpx;
			width: 650rpx;
		
			line-height: 5rpx;

			box-shadow: 2px 3px 6px 0px rgba(0, 14, 24, 0.09);
			display: flex;
			flex-direction: column;

			background-color: $uni-bg-color;
textarea{
	width: 100%;
border: 1px solid #09BB07;
        height: 650rpx;
        word-wrap : break-word;
}
		}

		.butimg {
			width: 70rpx;
			height: 70rpx;
			padding: 0 20rpx;
			margin: 0 10rpx;

		}

		.list_txt {

			width: 100%;
			padding: 20rpx;

			line-height: 5rpx;
		}
	}
</style>
