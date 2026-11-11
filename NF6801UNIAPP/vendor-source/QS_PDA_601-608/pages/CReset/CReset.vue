<template>
	<view class="content">

		<view class="input_data_list">

			<textarea :value="strings">

			    　　</textarea>
			<!--  <text class="list_txt"  v-for="item in listarr">{{item.data}}</text> -->
		</view>
		<view class="buttx"  style="-webkit-flex-wrap: wrap;flex-wrap: wrap;">
			<!-- 			<view class="print_but"><image class="butimg" src="../../static/printf.png"></image><text>打印</text></view> -->
			<view class=" print_but" @click="creset()">
				<image class="butimg" src="../../static/fids.png"></image><text>{{$t('index_icreset_text')}}</text>
			</view>

			<view class=" print_but" @click="getCicreset()">
				<image class="butimg" src="../../static/fids.png"></image><text>{{$t('index_Cicreset_text')}}</text>
			</view>
			<view class=" print_but" @click="geticselect()">
				<image class="butimg" src="../../static/fids.png"></image><text>{{$t('index_icselect_text')}}</text>
			</view>
			<view class=" print_but" @click="geticname()">
				<image class="butimg" src="../../static/fids.png"></image><text>{{$t('index_icname_text')}}</text>
			</view>
			<view class=" print_but" @click="geticsex()">
				<image class="butimg" src="../../static/fids.png"></image><text>{{$t('index_icsextext')}}</text>
			</view>
			<view class=" print_but" @click="getnation()">
				<image class="butimg" src="../../static/fids.png"></image><text>{{$t('index_icnation_text')}}</text>
			</view>
			<view class=" print_but" @click="getuserNumber()">
				<image class="butimg" src="../../static/fids.png"></image><text>{{$t('index_icuserNumber_text')}}</text>
			</view>
			<view class="print_but" @click="scanclear()">
				<image class="butimg" src="../../static/16573c8afec57ca.png"></image>
				<text>{{$t('page_scan_button_clear')}}</text>
			</view>
		</view>
	</view>
</template>

<script>
	const Qs5502Module = uni.requireNativePlugin("Qs5502Module");
	var main, receiver, filter, srcimg, userimg;
	var _codeQueryTag = false;
	export default {
		data() {
			return {
				mobile: "",

				//	listarr: [{data:"333"},{data:"333"}],
				strings: "",

			}
		},
		onHide: function() {
			this.s5502Module.closeGpio("", result => {

				console.log('返回的数据用户点击确定destroyed' + result)
			})
			Qs5502Module.closeDevice("ok", result => {

				console.log('返回的数据用户点击确定onHide' + result)
			})
			this.stopReceiver();
		},

		destroyed: function() {


			Qs5502Module.closeGpio("", result => {

				console.log('返回的数据用户点击确定destroyed' + result)
			})

			Qs5502Module.closeDevice("", result => {

				console.log('返回的数据用户点击确定destroyed' + result)
			})
			/*页面退出时一定要卸载监听,否则下次进来时会重复，造成扫一次出2个以上的结果*/
			this.stopReceiver();
		},

		onShow: function() {
			uni.setNavigationBarTitle({
				title: this.$t('page_icreset_title')
			});
			/*页面退出时一定要卸载监听,否则下次进来时会重复，造成扫一次出2个以上的结果*/
			this.initScan()
			this.startReceiver();
			Qs5502Module.init("", result => {

				console.log('返回的数据pirntimg2:' + result.a)
			})
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
				filter.addAction("com.qs.scandode"); // 换你的广播动作  
				receiver = plus.android.implements('io.dcloud.feature.internal.reflect.BroadcastReceiver', {
					onReceive: function(context, intent) {
						plus.android.importClass(intent);

						let scandata = intent.getStringExtra("CRcata"); //

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
				this.strings += this.mobile;
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
	this.mobile="";
			},
			creset() {

				Qs5502Module.creset([0x1B,0x23,0x23,0x43,0x41,0x52,0x44,0x00], result => {

				})

			},
			getCicreset() {
			
				Qs5502Module.getCicreset([0x1B,0x23,0x23,0x43,0x41,0x52,0x44,0x15,0x00,0xa4,0x04,0x00,0x0f,0x73,0x78,0x31,0x2E,0x73,0x68,0x2E,0xC9,0xE7,0xBB,0xE1,0xB1,0xA3,0xD5,0xCF,0x00], result => {
			
				})
			
			},
			geticselect() {
			
				Qs5502Module.geticselect([0x1B,0x23,0x23,0x43,0x41,0x52,0x44,0x08,0x00,0xa4,0x02,0x00,0x02,0xEF,0x06,0x00], result => {
			
				})
			
			},
			geticname() {
			
				Qs5502Module.geticname([0x1B,0x23,0x23,0x43,0x41,0x52,0x44,0x06,0x00,0xb2,0x09,0x00,0x00,0x20], result => {
			
				})
			
			},
			geticsex() {
			
				Qs5502Module.geticsex([0x1B,0x23,0x23,0x43,0x41,0x52,0x44,0x06,0x00,0xb2,0x0a,0x00,0x00,0x03], result => {
			
				})
			
			},
			getnation() {
			
				Qs5502Module.getnation([0x1B,0x23,0x23,0x43,0x41,0x52,0x44,0x06,0x00,0xb2,0x0b,0x00,0x00,0x03], result => {
			
				})
			
			},
			getuserNumber() {
			
				Qs5502Module.getuserNumber([0x1B,0x23,0x23,0x43,0x41,0x52,0x44,0x06,0x00,0xb2,0x08,0x00,0x00,0x14], result => {
			
				})
			
			},
			scanclear: function() {
				this.strings = "";
				this.mobile="";
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
				Qs5502Module.closeDevice("ok", result => {


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
				justify-content:left;
				width: 300rpx;
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

			textarea {
				margin-top: 10rpx;
				width: 100%;
				border: 1px solid #09BB07;
				height: 400rpx;
				word-wrap: break-word;
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
