<template>
	<view class="content">
		<view class="content_titlex">{{$t('page_printf_content')}}</view>
		<view class="content_textinput"><input type="text" @input="replaceInput" v-model="changeValue"></view>
		<view class="content_titlex2"><text> {{$t('page_printf_set')}}</text></view>
		<!-- <view class="content_set1">
					<text>图像高度</text><input focus placeholder="输入高度"/>
					<text>图像高度</text><input focus placeholder="输入高度"/>
				</view> -->
		<view class="content_set1"> <button type="default" @click="printfRQCODE()">{{$t('page_printf_rqcode')}}</button>
			<button type="default" @click="printBarCode()">{{$t('page_printf_barcode')}}</button>

		</view>
		<!-- <view class="content_set1"> <button type="default" @click="black_test()">{{$t('page_printf_set')}}</button>
			<text>{{$t('page_printf_concentration')}}</text>
			<input focus placeholder="0x27" @input="Inputset" v-model="Inputsetvalue" />
			<view>
				<switch checked @change="switch1Change" />

			</view>
		</view> -->
		<view class="content_set1"><button type="default" @click="printimg()">{{$t('page_printf_img')}}</button>
			<button type="default" @click="pirnttext()">{{$t('page_printf_text')}}</button>
			<button type="default"  @click="pirnttest()">{{$t('page_printf_test')}}</button>
		</view>
	</view>
</template>

<script>
	const Qs601Module = uni.requireNativePlugin("Qs601Module");
	export default {
		data() {
			return {
				changeValue: '',
				Inputsetvalue: 0x27,
			}
		},
		onShow: function() {
			uni.setNavigationBarTitle({
				title: this.$t('page_printf_title')
			});
		

			/*页面退出时一定要卸载监听,否则下次进来时会重复，造成扫一次出2个以上的结果*/

			Qs601Module.init("",true,53);//true开启黑标，65是 黑标起始位设置，默认值

		},onHide: function() {
			Qs601Module.closeGpio("");
			Qs601Module.closeDevice("", result => {

				console.log('返回的数据用户点击确定onHide' + result)
			})

		},
		
		
		destroyed: function() {


			Qs601Module.closeGpio("");

			Qs601Module.closeDevice("", result => {

				console.log('返回的数据用户点击确定destroyed' + result)
			})
			/*页面退出时一定要卸载监听,否则下次进来时会重复，造成扫一次出2个以上的结果*/

		},
		methods: {
			switch1Change: function(e) {
				Qs601Module.black_set(true, result => {});//true 开启， false关闭
	//	Qs601Module.black_set(true, result => {console.log('返回的数据black_test:' + result)});
		
				console.log('switch1 发生 change 事件，携带值为', e.detail.value)
			},
			black_test: function() {
				Qs601Module.black_test(0x27, result => {});


			},
			replaceInput: function(event) {
				var value = event.detail.value;

				this.changeValue = value;

			},
			Inputset: function(event) {
				var value = event.detail.value;

				this.Inputsetvalue = value;
				
			},
			printBarCode() {


				console.log('返回的数据printBarCode:');
				Qs601Module.printBarCode(1 ,200, 60, "123456",true,true,5);//1居中，200宽，50高，123456内容，false显示内容,true分页走纸，5内容大小;
				
				
				// Qs601Module.pirnttext("姓名：张三", 1, 2,result => {
					
				// 	console.log('返回的数据:' + result.a)
				// })
			setTimeout(function() {
			  // 这里写要延时执行的代码
			  Qs601Module.SendCMD([ 0x1D, 0x0c ],result => {})
			}, 500); // 这里的 500 表示延时的时间，单位是毫秒
			},
			     // *
			     //     * 打印二维码
			     //     * @param 1 对齐方式，0 左对齐，1 居中，2右对齐
			     //     * @param 255 二维码宽度
			     //     * @param 255 二维码高度
			     //     * @param this.changeValue 二维码内容
			     //     * @param true 是否显示二维码内容
			     //     * @param false 打印完是否要撕纸，设为true时候会打印完多走3空行，把纸张推出来，方便撕纸操作,只有关闭标签/黑标功能时有效
			     //     * @param 21  二维码内容大小
				//		* @param false 打印完是否要撕纸，设为true时候会打印完多走3空行，把纸张推出来，方便撕纸操作,只有关闭标签/黑标功能时有效
			        
			printfRQCODE() {
				// 	Qs601Module.pirnttext("姓名：张三  ", 1, 2,result => {
					
				// 	console.log('返回的数据:' + result.a)
				// })
				// console.log('返回的数据printBarCode:');
			//	Qs601Module.printfRQCODE(1, 250, 250, this.changeValue, true,false, 21,false);
				
				Qs601Module.printQR(0,"this.changeValue");
			

			},
			pirnttext() {
				console.log('返回的数据pirnttext:' + this.changeValue);
			
			        // 添加内容
			//        mIntent_text.putExtra("text", msg);
			//        // 添加对齐方式，0为左对齐，1为居中，2为居右
			//        mIntent_text.putExtra("align", 0);
			//        // 添加文字大小设置， 1为正常字体，2为双倍字体
				Qs601Module.pirnttext(this.changeValue+"\n\n", 1, 2,result => {})
						// setTimeout(function() {
						//   // 这里写要延时执行的代码
						//   Qs601Module.SendCMD([0x20,0x0a, 0x0a, 0x0a],result => {})
						// }, 500); // 这里的 500 表示延时的时间，单位是毫秒
					Qs601Module.pirnttext(this.changeValue+"\n\n", 1, 2,result => {})

			},pirnttest(){
				
				/*标签打印
									 *attrs:
									 *width:打印页宽度，建议范围：0~390
									 *height:打印页高度，范围：大于0
									 *elements:
									 *type:打印类型(1文本,2一维条码,3二维码,4虚线,5实线)
									 *data:打印内容
									 *width:打印区域宽度
									 *x:打印区域左上角X坐标
									 *y:打印区域左上角Y坐标
									 *stopX:终点x坐标
								     *stopY:终点y坐标
									 *size:文本字体大小，单位px，例：21
									 *strokeWidth:线条粗细值
									 *isDotted:0=实线，1=虚线
									 *width:一维条码宽度、虚线(或实线)长度
									 *height:一维条码高度、虚线(或实线)粗细
									 *side:二维码默认为正方形，side为二维码边长值
									 *dispalyCode：是否打印码值（0=不打印，1=打印）
									 fontSize:码值字体大小，默认值：21
									 */
								// var data = {  //58纸
								// 			"attrs" : {
								// 				"width" : 384, // 打印页宽度，建议范围：0~384
								// 				"height" : 256 // 打印页高度，范围：大于0
								// 			},
								// 			"elements" : [ 
								// 			{
								// 				"type" : 4, // 第1条竖线
								// 				"x" : 6, // 始点x坐标
								// 				"y" : 6, // 始点y坐标
								// 				"stopX" : 6, // 终点x坐标
								// 				"stopY" : 248, // 终点y坐标
								// 				"strokeWidth" : 1, // 线条粗细值
								// 				"isDotted" : 0 // 0=实线，1=虚线
								// 			}, {
								// 				"type" : 4, // 第2条竖线
								// 				"x" : 102, // 始点x坐标
								// 				"y" : 6, // 始点y坐标
								// 				"stopX" : 102, // 终点x坐标
								// 				"stopY" : 248, // 终点y坐标
								// 				"strokeWidth" : 1, // 线条粗细值
								// 				"isDotted" : 0 // 0=实线，1=虚线
								// 			}, {
								// 				"type" : 4, // 第3条竖线
								// 				"x" : 254, // 始点x坐标
								// 				"y" : 6, // 始点y坐标
								// 				"stopX" : 254, // 终点x坐标
								// 				"stopY" : 248, // 终点y坐标
								// 				"strokeWidth" : 1, // 线条粗细值
								// 				"isDotted" : 0 // 0=实线，1=虚线
								// 			}, {
								// 				"type" : 4, // 第4条竖线
								// 				"x" : 384, // 始点x坐标
								// 				"y" : 6, // 始点y坐标
								// 				"stopX" : 384, // 终点x坐标
								// 				"stopY" : 248, // 终点y坐标
								// 				"strokeWidth" : 1, // 线条粗细值
								// 				"isDotted" : 0 // 0=实线，1=虚线
								// 			}, {
								// 				"type" : 4, // 第1条横线
								// 				"x" : 6, // 始点x坐标
								// 				"y" : 6, // 始点y坐标
								// 				"stopX" : 384, // 终点x坐标
								// 				"stopY" : 6, // 终点y坐标
								// 				"strokeWidth" : 1, // 线条粗细值
								// 				"isDotted" : 0 // 0=实线，1=虚线
								// 			}, {
								// 				"type" : 4, // 第2条横线
								// 				"x" : 6, // 始点x坐标
								// 				"y" : 44, // 始点y坐标
								// 				"stopX" : 254, // 终点x坐标
								// 				"stopY" : 44, // 终点y坐标	
								// 				"strokeWidth" : 1, // 线条粗细值
								// 				"isDotted" : 0 // 0=实线，1=虚线
								// 			}, {
								// 				"type" : 4, // 第3条横线
								// 				"x" : 6, // 始点x坐标
								// 				"y" : 80, // 始点y坐标
								// 				"stopX" : 254, // 终点x坐标
								// 				"stopY" : 80, // 终点y坐标
								// 				"strokeWidth" : 1, // 线条粗细值
								// 				"isDotted" : 0 // 0=实线，1=虚线
								// 			}, {
								// 				"type" : 4, // 第4条横线
								// 				"x" : 6, // 始点x坐标
								// 				"y" : 112, // 始点y坐标
								// 				"stopX" : 254, // 终点x坐标
								// 				"stopY" : 112, // 终点y坐标
								// 				"strokeWidth" : 1, // 线条粗细值
								// 				"isDotted" : 0 // 0=实线，1=虚线
								// 			}, {
								// 				"type" : 4, // 第5条横线
								// 				"x" : 6, // 始点x坐标
								// 				"y" : 144, // 始点y坐标
								// 				"stopX" : 254, // 终点x坐标
								// 				"stopY" : 144, // 终点y坐标
								// 				"strokeWidth" : 1, // 线条粗细值
								// 				"isDotted" : 0 // 0=实线，1=虚线
								// 			}, {
								// 				"type" : 4, // 第6条横线
								// 				"x" : 6, // 始点x坐标
								// 				"y" : 178, // 始点y坐标
								// 				"stopX" : 254, // 终点x坐标
								// 				"stopY" : 178, // 终点y坐标
								// 				"strokeWidth" : 1, // 线条粗细值
								// 				"isDotted" : 0 // 0=实线，1=虚线
								// 			}, {
								// 				"type" : 4, // 第7条横线
								// 				"x" : 6, // 始点x坐标
								// 				"y" : 208, // 始点y坐标
								// 				"stopX" : 254, // 终点x坐标
								// 				"stopY" : 208, // 终点y坐标
								// 				"strokeWidth" : 1, // 线条粗细值
								// 				"isDotted" : 0 // 0=实线，1=虚线
								// 			}, {
								// 				"type" : 4, // 第8条横线
								// 				"x" : 6, // 始点x坐标
								// 				"y" : 248, // 始点y坐标
								// 				"stopX" : 384, // 终点x坐标
								// 				"stopY" : 248, // 终点y坐标
								// 				"strokeWidth" : 1, // 线条粗细值
								// 				"isDotted" : 0 // 0=实线，1=虚线
								// 			}, {
								// 				"type" : 1, // 文本
								// 				"data" : "物料型号", // 打印内容
								// 				"width" : 190, // 打印区域宽度
								// 				"x" : 12, // 打印区域左上角X坐标
								// 				"y" : 12, // 打印区域左上角Y坐标
								// 				"size" : 21 // 字体大小
								// 			}, {
								// 				"type" : 1, // 文本
								// 				"data" : "T5010L-25", // 打印内容
								// 				"width" : 190, // 打印区域宽度
								// 				"x" : 108, // 打印区域左上角X坐标
								// 				"y" : 12, // 打印区域左上角Y坐标
								// 				"size" : 21 // 字体大小
								// 			}, {
								// 				"type" : 1, // 文本
								// 				"data" : "月份标签粘\n贴处", // 打印内容
								// 				"width" : 190, // 打印区域宽度
								// 				"x" : 268, // 打印区域左上角X坐标
								// 				"y" : 12, // 打印区域左上角Y坐标
								// 				"size" : 21 // 字体大小
								// 			}, {
								// 				"type" : 1, // 文本
								// 				"data" : "批次批号", // 打印内容
								// 				"width" : 190, // 打印区域宽度
								// 				"x" : 12, // 打印区域左上角X坐标
								// 				"y" : 48, // 打印区域左上角Y坐标
								// 				"size" : 21 // 字体大小
								// 			}, {
								// 				"type" : 1, // 文本
								// 				"data" : "20LOK2713XY", // 打印内容
								// 				"width" : 190, // 打印区域宽度
								// 				"x" : 108, // 打印区域左上角X坐标
								// 				"y" : 48, // 打印区域左上角Y坐标
								// 				"size" : 21 // 字体大小
								// 			}, {
								// 				"type" : 1, // 文本
								// 				"data" : "物料规格", // 打印内容
								// 				"width" : 190, // 打印区域宽度
								// 				"x" : 12, // 打印区域左上角X坐标
								// 				"y" : 84, // 打印区域左上角Y坐标
								// 				"size" : 21 // 字体大小
								// 			}, {
								// 				"type" : 1, // 文本
								// 				"data" : "1240mm*200M", // 打印内容
								// 				"width" : 190, // 打印区域宽度
								// 				"x" : 108, // 打印区域左上角X坐标
								// 				"y" : 84, // 打印区域左上角Y坐标
								// 				"size" : 21 // 字体大小
								// 			}, {
								// 				"type" : 3, // 二维码
								// 				"data" : "123456", // 打印内容
								// 				"side" : 160, // 二维码边长
								// 				"x" : 268, // 打印区域左上角X坐标
								// 				"y" : 84, // 打印区域左上角Y坐标
								// 				"displayCode" : 1, // 0不打印码值,1打印码值
								// 				"fontSize":21 //码值字体大小，默认值：21
								// 			}, {
								// 				"type" : 1, // 文本
								// 				"data" : "物料数量", // 打印内容
								// 				"width" : 190, // 打印区域宽度
								// 				"x" : 12, // 打印区域左上角X坐标
								// 				"y" : 116, // 打印区域左上角Y坐标
								// 				"size" : 21 // 字体大小
								// 			}, {
								// 				"type" : 1, // 文本
								// 				"data" : "1卷", // 打印内容
								// 				"width" : 190, // 打印区域宽度
								// 				"x" : 108, // 打印区域左上角X坐标
								// 				"y" : 116, // 打印区域左上角Y坐标
								// 				"size" : 21 // 字体大小
								// 			}, {
								// 				"type" : 1, // 文本
								// 				"data" : "生产日期", // 打印内容
								// 				"width" : 190, // 打印区域宽度
								// 				"x" : 12, // 打印区域左上角X坐标
								// 				"y" : 148, // 打印区域左上角Y坐标
								// 				"size" : 21 // 字体大小
								// 			}, {
								// 				"type" : 1, // 文本
								// 				"data" : "2018-11-27", // 打印内容
								// 				"width" : 190, // 打印区域宽度
								// 				"x" : 108, // 打印区域左上角X坐标
								// 				"y" : 148, // 打印区域左上角Y坐标
								// 				"size" : 21 // 字体大小
								// 			}, {
								// 				"type" : 1, // 文本
								// 				"data" : "操作人员", // 打印内容
								// 				"width" : 190, // 打印区域宽度
								// 				"x" : 12, // 打印区域左上角X坐标
								// 				"y" : 180, // 打印区域左上角Y坐标
								// 				"size" : 21 // 字体大小
								// 			}, {
								// 				"type" : 1, // 文本
								// 				"data" : "系统管理员", // 打印内容
								// 				"width" : 190, // 打印区域宽度
								// 				"x" : 108, // 打印区域左上角X坐标
								// 				"y" : 180, // 打印区域左上角Y坐标
								// 				"size" : 21 // 字体大小
								// 			}, {
								// 				"type" : 1, // 文本
								// 				"data" : "备注内容", // 打印内容
								// 				"width" : 190, // 打印区域宽度
								// 				"x" : 12, // 打印区域左上角X坐标
								// 				"y" : 212, // 打印区域左上角Y坐标
								// 				"size" : 21 // 字体大小
								// 			}, {
								// 				"type" : 1, // 文本
								// 				"data" : "", // 打印内容
								// 				"width" : 190, // 打印区域宽度
								// 				"x" : 108, // 打印区域左上角X坐标
								// 				"y" : 212, // 打印区域左上角Y坐标
								// 				"size" : 21 // 字体大小
								// 			}]
								// 		};
								
								
								var data1 = {//80纸
											"attrs" : {
												"width" : 524, // 打印页宽度，建议范围：0~384
												"height" : 380 // 打印页高度，范围：大于0
											},
											"elements" : [ 
											{
												"type" : 4, // 第1条竖线
												"x" : 2, // 始点x坐标
												"y" : 2, // 始点y坐标
												"stopX" : 2, // 终点x坐标
												"stopY" : 350, // 终点y坐标
												"strokeWidth" : 1, // 线条粗细值
												"isDotted" : 0 // 0=实线，1=虚线
											},  {
												"type" : 4, // 第2条竖线
												"x" : 120, // 始点x坐标
												"y" : 2, // 始点y坐标
												"stopX" : 120, // 终点x坐标
												"stopY" : 350, // 终点y坐标
												"strokeWidth" : 1, // 线条粗细值
												"isDotted" : 0 // 0=实线，1=虚线
											}, {
												"type" : 4, // 第3条竖线
												"x" : 258, // 始点x坐标
												"y" : 50, // 始点y坐标
												"stopX" : 258, // 终点x坐标
												"stopY" : 350, // 终点y坐标
												"strokeWidth" : 1, // 线条粗细值
												"isDotted" : 0 // 0=实线，1=虚线
											},{
												"type" : 4, // 第3条竖线
												"x" : 358, // 始点x坐标
												"y" : 50, // 始点y坐标
												"stopX" : 358, // 终点x坐标
												"stopY" : 200, // 终点y坐标
												"strokeWidth" : 1, // 线条粗细值
												"isDotted" : 0 // 0=实线，1=虚线
											},{
												"type" : 4, // 第3条竖线
												"x" : 515, // 始点x坐标
												"y" : 2, // 始点y坐标
												"stopX" : 515, // 终点x坐标
												"stopY" : 350, // 终点y坐标
												"strokeWidth" : 1, // 线条粗细值
												"isDotted" : 0 // 0=实线，1=虚线
											}, {
												"type" : 4, // 第1条横线
												"x" : 2, // 始点x坐标
												"y" : 2, // 始点y坐标
												"stopX" : 515, // 终点x坐标
												"stopY" :2, // 终点y坐标
												"strokeWidth" : 1, // 线条粗细值
												"isDotted" : 0 // 0=实线，1=虚线
											}, {
												"type" : 4, // 第2条横线
												"x" : 2, // 始点x坐标
												"y" : 50, // 始点y坐标
												"stopX" : 515, // 终点x坐标
												"stopY" : 50, // 终点y坐标
												"strokeWidth" : 1, // 线条粗细值
												"isDotted" : 0 // 0=实线，1=虚线
											}, {
												"type" : 4, // 第3条横线
												"x" : 2, // 始点x坐标
												"y" : 100, // 始点y坐标
												"stopX" : 515, // 终点x坐标
												"stopY" : 100, // 终点y坐标
												"strokeWidth" : 1, // 线条粗细值
												"isDotted" : 0 // 0=实线，1=虚线
											}, {
												"type" : 4, // 第4条横线
												"x" : 2, // 始点x坐标
												"y" : 150, // 始点y坐标
												"stopX" : 515, // 终点x坐标
												"stopY" : 150, // 终点y坐标
												"strokeWidth" : 1, // 线条粗细值
												"isDotted" : 0 // 0=实线，1=虚线
											}, {
												"type" : 4, // 第5条横线
												"x" : 2, // 始点x坐标
												"y" : 200, // 始点y坐标
												"stopX" : 515, // 终点x坐标
												"stopY" : 200, // 终点y坐标
												"strokeWidth" : 1, // 线条粗细值
												"isDotted" : 0 // 0=实线，1=虚线
											}, {
												"type" : 4, // 第6条横线
												"x" : 2, // 始点x坐标
												"y" : 250, // 始点y坐标
												"stopX" : 258, // 终点x坐标
												"stopY" : 250, // 终点y坐标
												"strokeWidth" : 1, // 线条粗细值
												"isDotted" : 0 // 0=实线，1=虚线
											}, {
												"type" : 4, // 第7条横线
												"x" : 2, // 始点x坐标
												"y" : 300, // 始点y坐标
												"stopX" : 258, // 终点x坐标
												"stopY" : 300, // 终点y坐标
												"strokeWidth" : 1, // 线条粗细值
												"isDotted" : 0 // 0=实线，1=虚线
											},{
												"type" : 4, // 第1条横线
												"x" : 2, // 始点x坐标
												"y" : 350, // 始点y坐标
												"stopX" : 515, // 终点x坐标
												"stopY" : 350, // 终点y坐标
												"strokeWidth" : 1, // 线条粗细值
												"isDotted" : 0 // 0=实线，1=虚线
											}
											, {
												"type" : 1, // 文本
												"data" : "项目编号", // 打印内容
												"width" : 190, // 打印区域宽度
												"x" : 20, // 打印区域左上角X坐标
												"y" : 20, // 打印区域左上角Y坐标
												"size" : 21 // 字体大小
											}, {
												"type" : 1, // 文本
												"data" : "采购编号", // 打印内容
												"width" : 190, // 打印区域宽度
												"x" : 20, // 打印区域左上角X坐标
												"y" : 65, // 打印区域左上角Y坐标
												"size" : 21 // 字体大小
											}, {
												"type" : 1, // 文本
												"data" : "HF2106SCP0-187", // 打印内容
												"width" : 190, // 打印区域宽度
												"x" : 125, // 打印区域左上角X坐标
												"y" : 65, // 打印区域左上角Y坐标
												"size" : 16 // 字体大小
											}, {
												"type" : 1, // 文本
												"data" : "供应商", // 打印内容
												"width" : 190, // 打印区域宽度
												"x" : 270, // 打印区域左上角X坐标
												"y" : 65, // 打印区域左上角Y坐标
												"size" : 21 // 字体大小
											},  {
												"type" : 1, // 文本
												"data" : "苏州永良", // 打印内容
												"width" : 190, // 打印区域宽度
												"x" : 388, // 打印区域左上角X坐标
												"y" : 65, // 打印区域左上角Y坐标
												"size" : 21 // 字体大小
											},{
												"type" : 1, // 文本
												"data" : "规格", // 打印内容
												"width" : 190, // 打印区域宽度
												"x" : 45, // 打印区域左上角X坐标
												"y" : 115, // 打印区域左上角Y坐标
												"size" : 21 // 字体大小
											}, {
												"type" : 1, // 文本
												"data" : "SC01105207", // 打印内容
												"width" : 190, // 打印区域宽度
												"x" : 125, // 打印区域左上角X坐标
												"y" : 115, // 打印区域左上角Y坐标
												"size" : 16 // 字体大小
											},{
												"type" : 1, // 文本
												"data" : "料号", // 打印内容
												"width" : 190, // 打印区域宽度
												"x" : 270, // 打印区域左上角X坐标
												"y" : 115, // 打印区域左上角Y坐标
												"size" : 21 // 字体大小
											},{
												"type" : 1, // 文本
												"data" : "401010-8683", // 打印内容
												"width" : 190, // 打印区域宽度
												"x" : 370, // 打印区域左上角X坐标
												"y" : 115, // 打印区域左上角Y坐标
												"size" : 21 // 字体大小
											}, {
												"type" : 1, // 文本
												"data" : "名称", // 打印内容
												"width" : 190, // 打印区域宽度
												"x" : 45, // 打印区域左上角X坐标
												"y" : 160, // 打印区域左上角Y坐标
												"size" : 21 // 字体大小
											},  {
												"type" : 1, // 文本
												"data" : "加强筋正", // 打印内容
												"width" : 190, // 打印区域宽度
												"x" : 140, // 打印区域左上角X坐标
												"y" : 160, // 打印区域左上角Y坐标
												"size" : 21 // 字体大小
											},  {
												"type" : 1, // 文本
												"data" : "备注", // 打印内容
												"width" : 190, // 打印区域宽度
												"x" : 270, // 打印区域左上角X坐标
												"y" : 160, // 打印区域左上角Y坐标
												"size" : 21 // 字体大小
											},  {
												"type" : 1, // 文本
												"data" : "订单发货/补货", // 打印内容
												"width" : 190, // 打印区域宽度
												"x" : 370, // 打印区域左上角X坐标
												"y" : 160, // 打印区域左上角Y坐标
												"size" : 21 // 字体大小
											},{
												"type" : 1, // 文本
												"data" : "数量", // 打印内容
												"width" : 190, // 打印区域宽度
												"x" : 45, // 打印区域左上角X坐标
												"y" : 210, // 打印区域左上角Y坐标
												"size" : 21 // 字体大小
											},{
												"type" : 1, // 文本
												"data" : "9 PCS", // 打印内容
												"width" : 190, // 打印区域宽度
												"x" : 140, // 打印区域左上角X坐标
												"y" : 210, // 打印区域左上角Y坐标
												"size" : 21 // 字体大小
											}, {
												"type" : 1, // 文本
												"data" : "生产日期", // 打印内容
												"width" : 190, // 打印区域宽度
												"x" : 20, // 打印区域左上角X坐标
												"y" : 270, // 打印区域左上角Y坐标
												"size" : 21 // 字体大小
											}, {
												"type" : 1, // 文本
												"data" : "2022/7/27", // 打印内容
												"width" : 190, // 打印区域宽度
												"x" : 140, // 打印区域左上角X坐标
												"y" : 270, // 打印区域左上角Y坐标
												"size" : 21 // 字体大小
											},
											{
												"type" : 1, // 文本
												"data" : "有效日期", // 打印内容
												"width" : 190, // 打印区域宽度
												"x" : 20, // 打印区域左上角X坐标
												"y" : 320, // 打印区域左上角Y坐标
												"size" : 21 // 字体大小
											},{
												"type" : 1, // 文本
												"data" : "2024/7/27", // 打印内容
												"width" : 190, // 打印区域宽度
												"x" : 140, // 打印区域左上角X坐标
												"y" : 320, // 打印区域左上角Y坐标
												"size" : 21 // 字体大小
											}
											, {
												"type" : 3, // 二维码
												"data" : "SINEVA-FG(202105)0025", // 打印内容
												"side" : 180, // 二维码边长
												"x" : 320, // 打印区域左上角X坐标
												"y" : 210, // 打印区域左上角Y坐标
												"displayCode" : 0, // 0不打印码值,1打印码值
												"fontSize":21 //码值字体大小，默认值：21
											},
											{
												"type" : 1, // 文本
												"data" : "SINEVA-FG(202105)0025", // 打印内容
												"width" : 280, // 打印区域宽度
												"x" : 200, // 打印区域左上角X坐标
												"y" : 20, // 打印区域左上角Y坐标
												"size" : 21 // 字体大小
											}
											]
											
										};
										//data 要打印的数，true 走到下一黑标位置
				Qs601Module.printPaper(data1,true);
			},
			printimg() {
					

				let bitmap = new plus.nativeObj.Bitmap('test');
				bitmap.load('static/test2.png',
					() => {

						console.log('图片加载成功')
						Qs601Module.pirntimg(
							bitmap.toBase64Data(), {

								"align": 1,
								"width": 300,
								"height": 200
							},//toBase64Data位图数据，1位置（0左  1中  2右 ），384宽dp，225高dp

							result => {

						// 	//	await this.delay(500); // 等行500ms
						// setTimeout(function() {
						//   // 这里写要延时执行的代码
						//   Qs601Module.SendCMD([ 0x1D, 0x0c ],result => {})
						// }, 2000); // 这里的 500 表示延时的时间，单位是毫秒
							})


						bitmap.clear()

					},
					(res) => {
						console.log('图片加载失败')
						console.log(res)
						bitmap.clear()
					}
				)
			}


		}
	}
	
	

	 
	function checkPermission(permissions, permissionName, success) {
		plus.android.requestPermissions(permissions, function(e) {
			if (e.deniedAlways.length > 0) { //权限被永久拒绝
				// 弹出提示框解释为何需要读写手机储存权限，引导用户打开设置页面开启
				uni.showModal({
					title: permissionName + '权限',
					content: '您可能拒绝了' + permissionName + '权限或您的设备无需设置权限',
					success: function(res) {
						if (res.confirm) {
							// console.log('用户点击确定');
						} else if (res.cancel) {
							// console.log('用户点击取消');
						}
					}
				});
			}
			if (e.deniedPresent.length > 0) { //权限被临时拒绝
				plus.android.requestPermissions(permissions)
			}
			if (e.granted.length > 0) { //权限被允许
				if (success) {
					success();
				}
			}
		}, function(e) {
			console.log('checkPermission error:', e);
		});
	}
	
</script>

<style lang="scss">
	page {
		background-color: #F8F8FA;
	}

	.content {
		display: flex;
		flex-direction: column;


		width: 100%;

		background: #F8F8FA;

		.content_titlex {
			text-align: left;
			padding-left: 20rpx;
			font-size: 22rpx;
			font-family: Source Han Sans CN;
			font-weight: 400;
			color: #808080;
			line-height: 62rpx;
		}

		.content_textinput {
			width: 90%;
			padding-left: 20rpx;
			margin: 0 10rpx;
			height: 66rpx;
			background: #FFFFFF;
			border: 1rpx solid #808080;
			border-radius: 5rpx;
			text-align: left;

			text {
				border-radius: 10rpx;
				text-align: left;
			}
		}

		.content_titlex2 {
			margin: 20rpx 20rpx;
			text-align: center;
			width: 100%;
			height: 37rpx;
			font-size: 40rpx;
			font-family: Source Han Sans CN;
			font-weight: 400;
			color: #333333;
			line-height: 62rpx;
		}
	}

	.content_set1 {

		display: flex;
		flex-direction: row;
		align-items: center;
		justify-content: center;
		margin: 10rpx;
		text-align: center;

		button {
			margin: 15rpx;
			width: 200rpx;
			padding: 0 1rpx;
			border: 1px solid #18A29B;
			border-radius: 10rpx;
			font-size: 32rpx
		}

		text {
			text-align: center;
			width: 130rpx;
			height: 27rpx;
			font-size: 28rpx;
			font-family: Source Han Sans CN;
			font-weight: 400;
			color: #666666;
			line-height: 62rpx;
			padding-right: 2rpx;
			padding-left: 20rpx;

		}

		input {

			width: 187rpx;
			height: 66rpx;
			background: #FFFFFF;
			border: 1rpx solid #808080;
			border-radius: 10rpx;
		}
	}
</style>