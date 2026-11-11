<template>
	<view class="content">
		<view class="input_data"><input v-model="mobile"   class="input_data_scan"/></view>
		<view class="input_data_list">
		<uni-table border ref="table" :loading="loading" >
						<uni-tr>
							<uni-th width="150" align="center">数据</uni-th>
							<uni-th width="150" align="center">数量</uni-th>
				
						</uni-tr>
						<uni-tr v-for="(item, index) in listarr" :key="index">
							<uni-td>{{ item.oodex }}</uni-td>
							<uni-td>
								<view class="name">{{ item.sizex }}</view>
							</uni-td>
						
						</uni-tr>
					</uni-table>
			<!-- <textarea :value="strings">

			    　　</textarea> -->
							   <!--  <text class="list_txt"  v-for="item in listarr">{{item.data}}</text> -->
			</view>
		<view class="buttx">
			<!-- 			<view class="print_but"><image class="butimg" src="../../static/printf.png"></image><text>打印</text></view> -->
			<view class=" print_but" @click="scan()">
				<image class="butimg" src="../../static/scan.png"></image><text>连接</text>
					</view>
					<view class=" print_but" @click="uhf_switch()">
						<image class="butimg" src="../../static/scan.png"></image><text>开关</text>
							</view>
			<view class="print_but" @click="scanclear()">
				<image class="butimg" src="../../static/16573c8afec57ca.png"></image><text>清空</text>
			</view>
		</view>
	<uni-group title="设置"mode="card">
		
		<view class="buttx">
			<!-- 			<view class="print_but"><image class="butimg" src="../../static/printf.png"></image><text>打印</text></view> -->
		<!-- 	<view class=" print_but" @click="uhf_setSensitivity()">
				<image class="butimg" src="../../static/scan.png"></image><text>灵敏度</text>
					</view> -->
					<view class=" print_but" @click="uhf_emdop_rewriteepc()">
						<image class="butimg" src="../../static/scan.png"></image><text>写卡</text>
							</view>
			<view class="print_but" @click="uhf_setOutputPower()">
				<image class="butimg" src="../../static/16573c8afec57ca.png"></image><text>RF功率</text>
			</view>
		</view>
	<!-- 	<view class="buttx">
			<!-- 			<view class="print_but"><image class="butimg" src="../../static/printf.png"></image><text>打印</text></view> -->
			<view class=" print_but" @click="uhf_setWorkArea()">
				<image class="butimg" src="../../static/scan.png"></image><text>区域</text>
					</view>
			<view class="print_but" @click="uhf_setFrequency()">
				<image class="butimg" src="../../static/16573c8afec57ca.png"></image><text>频率</text>
			</view>
		</view> -->
		</uni-group>
	</view>
</template>

<script>
	const Qs5502Module = uni.requireNativePlugin("Qs5502Module");
	var main, receiver, filter, srcimg, userimg,listarr;

	var _codeQueryTag = false;
	export default {
		data() {
			return {
				mobile: "",
loading: false,

				listarr: [],
				strings:"",

			}
		},
		onHide: function() {
				Qs5502Module.uhf_switch(false);//true 开，return false;关
			Qs5502Module.uhf_closeGPIO(false);
	
			this.stopReceiver();
		},
		
		destroyed: function() {

	Qs5502Module.uhf_switch(false);//true 开，return false;关
				Qs5502Module.uhf_closeGPIO(false);

		
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
			Qs5502Module.inituhf("5502", result => {

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
				filter.addAction("com.qs.uhfaction"); // 换你的广播动作  
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
				
				//console.error("CANDATA：" + code)
				this.mobile = code;
			
				//this.strings+=this.mobile+"\n";
				var objf={oodex:code,sizex:1}
			//	if(this.listarr.length==0){this.listarr.push(objf)}
			const bol=this.listarr.findIndex(item => item.oodex==code);
			if(bol<0){this.listarr.push(objf)}
				this.listarr.forEach((_item,index) => {
					if(_item.oodex==code){
						 _item.sizex++;
						 console.log(index+"zzzzzzzzzzz：" + _item.oodex+"----"+_item.sizex)
						 	this.listarr.splice(index, 1,_item)
				
					}
				
								 	
								 })

			console.error("xxxxlength：" + this.listarr.length)
		
			},
			scan() {
				
				Qs5502Module.uhf_connect(true, result => {});
// 		Qs5502Module.uhf_switch(false);//true 开，return false;关
		
// 	Qs5502Module.uhf_setOutputPower(1);
// 	Qs5502Module.uhf_setSensitivity(1);
// Qs5502Module.uhf_setWorkArea(1);
// 	Qs5502Module.uhf_connect(true, result => {});
			},
			scanclear: function() {
				this.strings="";
				Qs5502Module.uhf_switch(false);//true 开，return false;关
			//this.listarr.splice(0);
			this.listarr= [];
						
			},
			
			uhf_switch: function() {
		
				Qs5502Module.uhf_switch(true);//true 开，return false;关
				
			},
			
			
			uhf_setSensitivity: function() {
				//0~3 灵敏度
				
				Qs5502Module.uhf_setSensitivity(1);
				
				},
			uhf_setOutputPower: function() {
	
				Qs5502Module.uhf_setOutputPower(2700);
				
			},
			uhf_emdop_rewriteepc:function(){//写入EPC 方法
					Qs5502Module.emdop_rewriteepc("00090005","20090105");	//参数1:旧的epc,参数2:新epc
			}
		,	
			uhf_setWorkArea: function() {
				//工作区域 0~4
		 // switch (position) {
		 // 					case 0:
		 // 						area = 1;
		 // 						China2：920125kHz~924875kHz
		 // 						break;
		 // 					case 1:
		 // 						area = 2;
		 // 						USA：902250kHz~927750kHz
		 // 						break;
		 // 					case 2:
		 // 						area = 3;
		 // 						Europe：865100kHz~867900kHz
		 // 						break;
		 // 					case 3:
		 // 						area = 4;
		 // 						China1：840125kHz~844875kHz
		 // 						break;
		 // 					case 4:
		 // 						area = 6;
		 // 						Korea：917700kHz~923300kHz
		 // 						break;
		 
		 // 					default:
		 // 						break;
		 // 				}
		//		Qs5502Module.uhf_setWorkArea(1);
				
			},
			
			uhf_setFrequency: function() {
			//设置频率 khz
			//this.listarr.splice(0);
				//Qs5502Module.uhf_setFrequency(0);
				
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

				margin-top: 10rpx;
				margin-left: 10rpx;
			
				border-radius: 10rpx;
				display: flex;
				flex-direction: row;
				align-items: center;
				justify-content: center;
				width: 280rpx;
				height: 80rpx;
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
        height: 500rpx;
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
