<template>
	<view class="content">

		<view class="idcard_front">
			<view class="user_info">

				<text>{{$t('page_idcard_INFO_NAME')}}:{{INFO_NAME}}</text>
				<text>{{$t('page_idcard_INFO_sex')}}:{{INFO_sex}}
					{{$t('page_idcard_INFO_nation')}}：{{INFO_nation}}</text>
				<text>{{$t('page_idcard_INFO_birth')}}:{{INFO_birth}}</text>
				<text>{{$t('page_idcard_INFO_address')}}：{{INFO_address}}</text>
				<text style="width: 600rpx;">{{$t('page_idcard_INFO_licid')}}：{{INFO_licid}}</text>
			</view>

			<view class="user_img">
				<image style=" background-color: #eeeeee;" :mode="mode" :src="'data:image/jpg;base64,' + srcimg"
					@error="handleError"></image>
				<!-- 	<image src="../../static/head.png"></image> -->
			</view>

		</view>
		<view class="idcard_reverse">
			<view class="idcard_reverse_r1">
				<image src="../../static/national.png"></image>
				<view class="idcard_reverse_text"><text class="test_l1">{{$t('page_idcard_country')}}</text><text
						class="test_l2">{{$t('page_idcard_idcarname')}}</text></view>
			</view>
			<view class="idcard_reverse_r2">
				<text>{{$t('page_idcard_INFO_depart')}}：{{INFO_depart}}</text><text>{{$t('page_idcard_INFO_expireDate')}}：{{INFO_expireDate}}</text>
			</view>

		</view>
		<view class="idcard_butlsit">
			<button type="default" @click="opencarid()" plain="true">{{$t('page_idcard_start')}}</button>
			<button type="default" @click="closedcarid()" plain="true">{{$t('page_idcard_stop')}}</button>
		</view>
	</view>
</template>

<script>
	const Qs601Module = uni.requireNativePlugin("Qs601Module")
	// var objstr = {
	// 				a: '111'
	// 			};
	var main, receiver, filter, srcimg, userimg;
	var _codeQueryTag = false;
	var model = "805-442" //805-442 805-483 805-440  1002


	export default {
		data() {
			return {
				srcimg: '',
				mode: 'aspectFill',
				title: 'uni-fab',
				directionStr: '垂直',
				horizontal: 'left',
				vertical: 'bottom',
				direction: 'horizontal',
				INFO_NAME: "",
				INFO_sex: "",
				INFO_birth: "",
				INFO_CANDATA: "",
				INFO_depart: "",
				INFO_licid: "",



				pattern: {
					color: '#7A7E83',
					backgroundColor: '#fff',
					selectedColor: '#007AFF',
					buttonColor: '#007AFF',
					iconColor: '#fff'
				},
				is_color_type: false,
				content: [{
						iconPath: '/static/g2.png',
						selectedIconPath: '/static/g2x.png',
						text: '打印',
						active: false
					},
					{
						iconPath: '/static/g3.png',
						selectedIconPath: '/static/g3x.png',
						text: '扫码',
						active: false
					},
					{
						iconPath: '/static/printf.png',
						selectedIconPath: '/static/printf.png',
						text: '打印图片',
						active: false
					}, {
						iconPath: '/static/printf.png',
						selectedIconPath: '/static/g8x.png',
						text: '读证',
						active: false
					}, {
						iconPath: '/static/usercont_4.png',
						selectedIconPath: '/static/g8x.png',
						text: '打印一维',
						active: false
					}, {
						iconPath: '/static/usercont_6.png',
						selectedIconPath: '/static/g8x.png',
						text: '打印二维',
						active: false
					},
				]

			}
		},
		created() {
			uni.setNavigationBarTitle({
				title: this.$t('page_idcard_title')
			});
		},
		onHide: function() {
			Qs601Module.closeGpio(model, result => {

				console.log('返回的数据用户点击确定destroyed' + result)
			})
			Qs601Module.closeIDRDGPIO(model)
			this.stopReceiver();
		},
		destroyed: function() {


			Qs601Module.closeGpio(model, result => {

				console.log('返回的数据用户点击确定destroyed' + result)
			})

			Qs601Module.closeIDRDGPIO(model)
			/*页面退出时一定要卸载监听,否则下次进来时会重复，造成扫一次出2个以上的结果*/
			this.stopReceiver();
		},
		onShow: function() {

			this.INFO_NAME = "";
			this.INFO_sex = "";
			this.INFO_birth = " ";
			this.INFO_address = "";
			this.INFO_nation = "";
			this.INFO_expireDate = "";
			this.INFO_depart = "";
			this.INFO_licid = "";
			/*页面退出时一定要卸载监听,否则下次进来时会重复，造成扫一次出2个以上的结果*/
			this.initScan()
			this.startReceiver();
			Qs601Module.init("", true, 52); //true开启黑标，65是 黑标起始位设置，默认值

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
						let name = intent.getStringExtra("name");
						let sex = intent.getStringExtra("sex")
						let address = intent.getStringExtra("address");
						let birth = intent.getStringExtra("birth");
						let expireDate = intent.getStringExtra("expireDate");
						let passNo = intent.getStringExtra("passNo");
						let depart = intent.getStringExtra("depart");
						let visaTimes = intent.getStringExtra("visaTimes");
						let nation = intent.getStringExtra("nation");
						let licid = intent.getStringExtra("licid");
						console.error("返回数据：expireDate" + expireDate);
						console.error("返回数据：passNo" + passNo);
						console.error("返回数据：depart" + depart);
						console.error("返回数据：visaTimes" + visaTimes);
						console.error("返回数据：nation" + nation);
						console.error("返回数据：licid" + licid);
						console.error("接收的条码数据：name" + name);
						_this.saveImgToLocal(intent.getStringExtra("bmpPhoto"));
						_this.queryCode(name, address, birth, sex, nation, expireDate, depart, licid); //身份证

					}
				});
			},
			opencarid: function() {
				Qs601Module.init("", true, 52); //true开启黑标，65是 黑标起始位设置，默认值
				this.initScan()
				this.startReceiver();

				Qs601Module.initidrd(model, result => {

					console.log('返回的数据用户点击确定' + result)
				})

			},

			closedcarid: function() {
				this.stopReceiver();
				Qs601Module.closeDevice(model, result => {

					this.INFO_NAME = "";
					this.INFO_sex = "";
					this.INFO_birth = " ";
					this.INFO_address = "";
					this.INFO_nation = "";
					this.INFO_expireDate = "";
					this.INFO_depart = "";
					this.INFO_licid = "";
					let bitmap = new plus.nativeObj.Bitmap('test');
					bitmap.load('static/head.png',
						() => {

							console.log('图片加载成功')
							this.saveImgToLocal(bitmap.toBase64Data());
							bitmap.clear()

						},
						(res) => {
							console.log('图片加载失败')
							console.log(res)
							bitmap.clear()
						}
					)

				})
				console.log('用户点击取消')
			},
			queryCode: function(_name, _address, _birth, _sex, _nation, _expireDate, _depart, _licid) {


				this.INFO_NAME = _name;
				this.INFO_sex = _sex;
				this.INFO_birth = _birth + " ";
				this.INFO_address = _address;
				this.INFO_nation = _nation;
				this.INFO_expireDate = _expireDate;
				this.INFO_depart = _depart;
				this.INFO_licid = _licid;


				//防重复  
				if (_codeQueryTag) return false;
				_codeQueryTag = true;
				setTimeout(function() {
					_codeQueryTag = false;
					// Qs601Module.pirnttext(name+":"+address+":"+birth, result => {

					// 	console.error('返回的数据address:' +address)
					// })
				}, 500);



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
			saveImgToLocal(base64) {

				this.srcimg = base64.replace(/[\r\n]/g, "");
				// const bitmap = new plus.nativeObj.Bitmap('test');
				// bitmap.loadBase64Data(
				// 	base64,
				// 	function() {
				// 		const url = '_doc/usernameimg.jpg'; // url建议用时间戳命名方式
				// 		console.log('url:', url);
				// 		bitmap.save(
				// 			url, {
				// 				overwrite: true // 是否覆盖
				// 				// quality: 'quality'  // 图片清晰度
				// 			},
				// 			i => {
				//                             //保存图片到系统相册
				// 			uni.saveImageToPhotosAlbum({
				// 					filePath: url,
				// 					success: function(reobj) {
				// 						uni.showToast({
				// 							title: '保存成功',
				// 							icon: 'none',
				// 						})
				// 						//	_this.userimg=reobj.path;
				// 									//console.log('1path===', _this.userimg);
				// 						bitmap.clear();
				// 		this.srcimg=this.url(reobj.path);


				// 					}
				// 				});

				// 			},
				// 			e => {
				// 				uni.showToast({
				// 					title: '保存失败',
				// 					icon: 'none'
				// 				})
				// 				console.log(e);
				// 				bitmap.clear();
				// 			}
				// 		);
				// 	},
				// 	e => {
				// 		console.log('保存失败', e);
				// 		bitmap.clear();
				// 	}
				// );

			},


		},
		onLoad() {
			// this.initScan()
			// this.startReceiver();
			// 	Qs601Module.init("初始化", result => {

			// 		console.log('返回的数据pirntimg2:' + result.a)
			// 	})



		}
	}
</script>

<style lang="scss">
	page {

		background: #F8F8FA;
	}

	.content {
		display: flex;

		flex-direction: column;
		align-items: center;
		justify-content: center;
		background: #F8F8FA;
		width: 100%;

		.idcard_front {
			display: flex;

			flex-direction: row;
			margin: 48rpx;
			width: 653rpx;
			height: 350rpx;
			border: 1rpx solid #18A29B;
			box-shadow: 4rpx 6rpx 13rpx 0rpx rgba(1, 1, 1, 0.09);

			border-radius: 12rpx;
			padding-top: 50rpx;
			padding-left: 48rpx;

			.user_info {
				display: flex;

				flex-direction: column;
				width: 440rpx;

				text {
					margin-top: 10rpx;



					font-size: 32rpx;
					font-family: Source Han Sans CN;
					font-weight: bold;
					color: #333333;

				}
			}

			.user_img {

				image {

					width: 182rpx;
					height: 224rpx;
				}
			}


		}

		.idcard_reverse {
			display: flex;

			flex-direction: column;
			padding-left: 48rpx;
			width: 654rpx;
			height: 400rpx;
			border: 1rpx solid #18A29B;
			box-shadow: 4rpx 6rpx 13rpx 0rpx rgba(1, 1, 1, 0.09);

			border-radius: 12rpx;

			.idcard_reverse_r1 {
				display: flex;

				flex-direction: row;
				align-items: center;
				justify-content: center;
				padding: 30rpx 20rpx;

				image {
					width: 98rpx;
					height: 103rpx;
				}

				.idcard_reverse_text {
					width: 520rpx;
					display: flex;
					text-align: center;
					flex-direction: column;
					align-items: center;
					justify-content: center;

					.test_l1 {
						margin-top: 20rpx;
						width: 439rpx;
						height: 32rpx;
						font-size: 32rpx;
						font-family: Source Han Serif CN;
						font-weight: bold;
						color: #333333;
						line-height: 50rpx;
					}

					.test_l2 {
						margin-top: 20rpx;
						width: 499rpx;
						height: 51rpx;
						font-size: 52rpx;
						font-family: Source Han Serif CN;
						font-weight: bold;
						color: #333333;
						line-height: 50rpx;
					}
				}

			}

			.idcard_reverse_r2 {
				padding-left: 20rpx;
				display: flex;

				flex-direction: column;

				text {
					margin-top: 36rpx;
					width: 100%;
					height: 36rpx;
					font-size: 32rpx;
					font-family: Source Han Sans CN;
					font-weight: bold;
					color: #333333;
					line-height: 36rpx;
				}

			}


		}

		.idcard_butlsit {

			display: flex;

			flex-direction: row;
			margin: 20rpx 0rpx;

			button {

				width: 300rpx;
				height: 79rpx;
				line-height: 79rpx;
				margin: 10rpx 10rpx;
				background-color: #18A29B;
				color: #FFFFFF;

			}
		}

	}
</style>