/* One-shot recovery policy only for a quality switch that began during playback. */
var JaQualityResume = (() => {
  function arm(video,intent) {
    return {eligible:!!video && !video.paused && !video.ended, intent, attempted:false, cancelled:false};
  }
  function sample(guard,video,intent,visible) {
    if(!guard || !guard.eligible || guard.attempted || guard.cancelled)return false;
    if(intent!==guard.intent){guard.cancelled=true;return false;}
    if(!visible || !video || !video.isConnected || video.ended || video.videoWidth<480 || video.videoHeight<270 || video.readyState<2)return false;
    if(!video.paused)return false;
    guard.attempted=true;return true;
  }
  return {arm,sample};
})();
if(typeof module!=='undefined')module.exports=JaQualityResume;
