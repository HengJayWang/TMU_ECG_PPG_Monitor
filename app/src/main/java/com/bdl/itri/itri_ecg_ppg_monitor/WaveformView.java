/***************************************
 * 
 * Android Bluetooth Oscilloscope
 * yus	-	projectproto.blogspot.com
 * September 2010
 *  
 ***************************************/

package com.bdl.itri.itri_ecg_ppg_monitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class WaveformView extends SurfaceView implements SurfaceHolder.Callback{
	
	// plot area size
	private final static int WIDTH = 1024;
	private final static int HEIGHT = 512;
	
	private static int[] dataECG = new int[WIDTH];
	private static int[] dataPPG = new int[WIDTH];
	private static int posECG = 128; //HEIGHT/2;
	private static int posPPG = 386; //HEIGHT/2;
	
	private WaveformPlotThread plot_thread;
	
	private Paint ECG_color = new Paint();
	private Paint PPG_color = new Paint();
	private Paint grid_paint = new Paint();
	private Paint cross_paint = new Paint();
	private Paint outline_paint = new Paint();

	public WaveformView(Context context, AttributeSet attrs){
		super(context, attrs);
		
		getHolder().addCallback(this);
		
		// initial values
		for(int x=0; x<WIDTH; x++){
			dataECG[x] = posECG;
			dataPPG[x] = posPPG;
		}
		
		plot_thread = new WaveformPlotThread(getHolder(), this);
		
		ECG_color.setColor(Color.YELLOW);
		PPG_color.setColor(Color.argb(255, 47, 189, 255));
		grid_paint.setColor(Color.rgb(100, 100, 100));
		cross_paint.setColor(Color.rgb(70, 100, 70));
		outline_paint.setColor(Color.GREEN);
	}
	
	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height){
		
	}
	
	@Override
	public void surfaceCreated(SurfaceHolder holder){
		plot_thread.setRunning(true);
		plot_thread.start();
	}
	
	@Override
	public void surfaceDestroyed(SurfaceHolder holder){
		boolean retry = true;
		plot_thread.setRunning(false);
		while (retry){
			try{
				plot_thread.join();
				retry = false;
			}catch(InterruptedException e){
				
			}
		}
	}
	
	@Override
	public void onDraw(Canvas canvas){
		Log.d("myTAG", "WaveformView.onDraw()");
		PlotPoints(canvas);
	}
	
	public void set_data(int[] data1, int[] data2 ){
		Log.d("myTAG", "WaveformView.set_data()");
		//plot_thread.setRunning(false);
		
		for( int x=0; x<WIDTH; x++ ){
			// channel 1
			if( x < ( data1.length ) ){
				dataECG[x] = HEIGHT-data1[x]+1;
			}else{
				dataECG[x] = posECG;
			}
			// channel 2
			if( x < (data2.length) ){
				dataPPG[x] = HEIGHT-data2[x]+1;
			}else{
				dataPPG[x] = posPPG;
			}
		}
		plot_thread.setRunning(true);
	}
	
	public void PlotPoints(Canvas canvas){
		// Log.d("myTAG", "WaveformView.PlotPoints()");
		if (canvas == null) { return; }
		// clear screen
		canvas.drawColor(Color.rgb(20, 20, 20));
		// draw vertical grids
	    for(int vertical = 1; vertical<10; vertical++){
	    	canvas.drawLine(
	    			vertical*(WIDTH/10)+1, 1,
	    			vertical*(WIDTH/10)+1, HEIGHT+1,
	    			grid_paint);
	    }
	    // draw horizontal grids
	    for(int horizontal = 1; horizontal<10; horizontal++){
	    	canvas.drawLine(
	    			1, horizontal*(HEIGHT/10)+1,
	    			WIDTH+1, horizontal*(HEIGHT/10)+1,
	    			grid_paint);
	    }
	    // draw outline
 		canvas.drawLine(0, 0, (WIDTH+1), 0, outline_paint);	// top
 		canvas.drawLine((WIDTH+1), 0, (WIDTH+1), (HEIGHT+1), outline_paint); //right
 		canvas.drawLine(0, (HEIGHT+1), (WIDTH+1), (HEIGHT+1), outline_paint); // bottom
 		canvas.drawLine(0, 0, 0, (HEIGHT+1), outline_paint); //left
 		
 		// plot data
		for(int x=0; x<(WIDTH-1); x++){
			ECG_color.setStrokeWidth(5);
			PPG_color.setStrokeWidth(5);
			canvas.drawLine(x+1, dataPPG[x], x+2, dataPPG[x+1], PPG_color);
			canvas.drawLine(x+1, dataECG[x], x+2, dataECG[x+1], ECG_color);
		}
	}
}
