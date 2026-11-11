<script>
	export default {
		onLaunch: function() {
			
		console.log('App Launch');
		
		var lan='zh'
		try{
			const res=uni.getSystemInfoSync();
			lan =res.language
			
		}catch(e){
			
			//TODO handle the exception
		}
		console.log('===='+lan);
		// if(lan=='en-US'){
		// 		console.log('====en-US'+lan);
		// 		this._i18n.locale='en-US';
			
		// }
		if(lan=='zh-CN'){
				console.log('====zh-CN'+lan);
			this._i18n.locale='zh-CN';
		}else{
			
				this._i18n.locale='en-US';
		}
		},
		onShow: function() {
			console.log('App Show')
		},
		onHide: function() {
			console.log('App Hide')
		}
	}
</script>

<style>
	/*每个页面公共css */
</style>
