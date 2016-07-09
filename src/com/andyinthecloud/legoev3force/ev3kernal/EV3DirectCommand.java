package com.andyinthecloud.legoev3force.ev3kernal;

import lejos.hardware.Button;
import lejos.hardware.motor.Motor;
import lejos.hardware.port.SensorPort;
import lejos.hardware.sensor.EV3TouchSensor;
import lejos.robotics.SampleProvider;
import lejos.robotics.filter.AbstractFilter;

/**
 * Utility class methods help move the Grabber EV3 robot around
 */
public class EV3DirectCommand {

	private static EV3TouchSensor SENSOR1 = new EV3TouchSensor(SensorPort.S1);
	
	public static void moveForward(int rotations)
	{
    	Motor.B.rotate((180 * rotations)*1, true);
    	Motor.C.rotate((180 * rotations)*1, true);
    	while (Motor.B.isMoving() || Motor.C.isMoving());
    	Motor.B.flt(true);
    	Motor.C.flt(true);
	}

	public static void moveBackwards(int rotations)
	{
    	Motor.B.rotate((180 * rotations)*-1, true);
    	Motor.C.rotate((180 * rotations)*-1, true);		
    	while (Motor.B.isMoving() || Motor.C.isMoving());
    	Motor.B.flt(true);
    	Motor.C.flt(true);
	}
	
	public static void release()
	{		
		// Grabbers already open?
		if(new SimpleTouch(SENSOR1).isPressed())
			return;
		// Release grabbers		
		Motor.A.rotate((180 * 5)*-1);
    	Motor.A.flt(true);		
	}

	public static void grab()
	{
		// Grabbers already grabbing something?
		if(new SimpleTouch(SENSOR1).isPressed()==false)
			return;
		Motor.A.rotate((180 * 5));		
    	Motor.A.flt(true);		
	}
	
	public static void turnLeft()
	{
    	Motor.B.rotate((int) (180 * 2.5)*-1, true);
    	Motor.C.rotate((int) (180 * 2.5)*1, true);
    	while (Motor.B.isMoving() || Motor.C.isMoving());
    	Motor.B.flt(true);
    	Motor.C.flt(true);
	}
	
	public static void turnRight()
	{
    	Motor.B.rotate((int) (180 * 2.5)*1, true);
    	Motor.C.rotate((int) (180 * 2.5)*-1, true);
    	while (Motor.B.isMoving() || Motor.C.isMoving());
    	Motor.B.flt(true);
    	Motor.C.flt(true);
	}
	
	public static void init()
	{
    	Motor.A.setSpeed(700);
    	Motor.B.setSpeed(700);
    	Motor.C.setSpeed(700);
    	moveForward(1);
	}
	
	public static void led(int parameter) 
	{
		Button.LEDPattern(parameter);		
	}    	

    public static void main(String[] args)
        	throws Exception
    {
    	init();
    	release();
    	moveForward(8);
    	turnLeft();
    	moveForward(9);
    	turnRight();
    	moveForward(8);
    	grab();    	
    }
    
    /**
     * See thread http://www.lejos.org/forum/viewtopic.php?f=21&t=6627
     */
    public static class SimpleTouch extends AbstractFilter {
      private float[] sample;

      public SimpleTouch(SampleProvider source) {
        super(source);
        sample = new float[sampleSize];
      }

      public boolean isPressed() {
        super.fetchSample(sample, 0);
        if (sample[0] == 0)
          return false;
        return true;
      }
    }
}
