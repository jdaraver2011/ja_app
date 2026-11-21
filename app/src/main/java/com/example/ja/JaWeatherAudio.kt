package com.example.ja

import android.content.Intent
import android.media.AudioManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

internal data class JaWeatherMusicTrack(val id:String,val title:String,val file:String)
internal val jaWeatherMusicTracks=listOf(
    JaWeatherMusicTrack("sunny","Sunny Afternoon","latest-observations--sunny-afternoon.mp3"),
    JaWeatherMusicTrack("fog","Morning Fog","latest-observations--morning-fog.mp3"),
    JaWeatherMusicTrack("drizzle","September Drizzle","latest-observations--september-drizzle.mp3")
)

/** Android owns asset/content URI playback, audio focus, repeat and volume; the browser stays silent. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun JaWeatherAudio(state:JaWeatherState){
    val context=LocalContext.current.applicationContext
    val owner=LocalLifecycleOwner.current
    var resumed by remember{mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    val track=jaWeatherMusicTracks.firstOrNull{it.id==state.musicChoice}?:jaWeatherMusicTracks.first()
    val uri=if(state.musicChoice=="custom"&&state.customMusic.isNotBlank())state.customMusic else "asset:///weatherstar/music/default/${track.file}"
    val player=remember(uri){ExoPlayer.Builder(context).build().apply{
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),true)
        setHandleAudioBecomingNoisy(true);repeatMode=Player.REPEAT_MODE_ONE
        volume=state.volume;setMediaItem(MediaItem.fromUri(uri));prepare()
    }}
    DisposableEffect(player,owner){
        JaMusicService.setVideoActive(true)
        val manager=context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        Log.i("JA_WEATHER_AUDIO","OPEN source=${if(state.musicChoice=="custom")"device" else track.id} volume=${state.volume} systemVolume=${manager.getStreamVolume(AudioManager.STREAM_MUSIC)} systemMuted=${manager.isStreamMute(AudioManager.STREAM_MUSIC)}")
        val listener=object:Player.Listener{
            override fun onPlaybackStateChanged(value:Int){if(value==Player.STATE_READY){state.musicError=null;Log.i("JA_WEATHER_AUDIO","READY durationMs=${player.duration}")}}
            override fun onIsPlayingChanged(playing:Boolean){Log.i("JA_WEATHER_AUDIO","PLAYING value=$playing positionMs=${player.currentPosition}")}
            override fun onPlayerError(error:PlaybackException){state.musicError="Weather audio unavailable. Select a bundled track or choose the device file again.";Log.e("JA_WEATHER_AUDIO","ERROR code=${error.errorCodeName}",error)}
        }
        val observer=LifecycleEventObserver{_,_->resumed=owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)}
        player.addListener(listener);owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer);player.removeListener(listener);player.release();Log.i("JA_WEATHER_AUDIO","RELEASE")}
    }
    SideEffect{player.volume=state.volume;player.playWhenReady=state.sound&&resumed}
    LaunchedEffect(player,resumed){if(resumed)repeat(3){delay(5000);Log.i("JA_WEATHER_AUDIO","PROGRESS playing=${player.isPlaying} positionMs=${player.currentPosition} suppression=${player.playbackSuppressionReason}")}}
}

@Composable
internal fun JaWeatherMusicSettings(state:JaWeatherState){
    val context=LocalContext.current
    var message by remember{mutableStateOf<String?>(null)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){
            try{
                context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val previous=state.customMusic
                state.saveCustomMusic(uri.toString());message="Device audio selected. It will repeat while weather is open."
                if(previous.isNotBlank()&&previous!=uri.toString())runCatching{context.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(previous),Intent.FLAG_GRANT_READ_URI_PERMISSION)}
            }catch(_:Exception){message="This file provider could not save access. Choose an audio file from device storage."}
        }
    }
    Text("Weather music",style=MaterialTheme.typography.titleMedium,color=Color.White)
    jaWeatherMusicTracks.forEach{track->
        TvFocusButton(onClick={state.selectMusic(track.id)},modifier=Modifier.widthIn(max=360.dp).fillMaxWidth(),
            colors=ButtonDefaults.buttonColors(containerColor=if(state.musicChoice==track.id)Color(0xFFE5A43B) else Color.DarkGray)){
            Text((if(state.musicChoice==track.id)"✓ " else "")+track.title,color=if(state.musicChoice==track.id)Color.Black else Color.White)
        }
    }
    TvFocusButton(onClick={try{picker.launch(arrayOf("audio/*"))}catch(_:android.content.ActivityNotFoundException){message="No file picker is installed on this device. Use a bundled track."}}){Text("Choose device audio file")}
    if(state.customMusic.isNotBlank())TvFocusButton(onClick={state.selectMusic("custom")}){Text(if(state.musicChoice=="custom")"✓ Device audio file" else "Use selected device audio file")}
    (message?:state.musicError)?.let{Text(it,color=Color.LightGray)}
    Text("Bundled music: netbymatt, Latest Observations (CC BY 4.0). Device files stay on this device.",style=MaterialTheme.typography.bodySmall,color=Color.LightGray)
}
