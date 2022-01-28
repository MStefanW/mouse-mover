import java.awt.MouseInfo;
import java.awt.PointerInfo;
import java.awt.Robot;

import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.function.BiConsumer;

public class MouseMomentum {
	
	private final float DECAY;
	private final int INTERVAL;
	private final float MOMENTUM_THRESHOLD;
	private final float ACTIVE_DECAY;
	private final int BOUNCE_THRESHOLD;
	private final int BOUNCE_CONFIDENCE_REQUIREMENT;
	private final float SENSITIVITY_MODIFIER;
	private final float MIN_VELOCITY;
	private final float MAX_VELOCITY;
	
	private Robot robot;
	
	private Point lastPos;
	private History<Point> differenceHistory;
	private History<Point> appliedVelocityHistory;
	private History<Vector> velocityHistory;
	private Vector velocityScraps;
	
	public static void main( String... args ) throws Exception {
		
		if ( args.length != 9 ) {
			System.out.println( "arg 1: decay (decimal, range 0-1, range 1+ accelerates)" );
			System.out.println( "arg 2: interval (integer, in milliseconds)" );
			System.out.println( "arg 3: momentum threshold velocity (decimal, in pixels per interval)" );
			System.out.println( "arg 4: active decay (decimal, range 1+, applies decay [active decay] times when moving pointer)" );
			System.out.println( "arg 5: bounce threshold velocity (integer, in pixels per interval)" );
			System.out.println( "arg 6: bounce confidence requirement (integer, in steps, range 1+)" );
			System.out.println( "arg 7: sensitivity (decimal, range 0-1, range 1+ gets pretty crazy)" );
			System.out.println( "arg 8: min velocity (decimal, in pixels per interval)");
			System.out.println( "arg 9: max velocity (decimal, in pixels per interval, value of 0 removes limit)");
			System.out.println( "suggested default: 0.95 15 50 5 5 2 0.1 0.1 0" );
			System.exit( 1 );
		}
		
		MouseMomentum m = new MouseMomentum(
			Float.parseFloat( args[ 0 ] ),
			Integer.parseInt( args[ 1 ] ),
			Float.parseFloat( args[ 2 ] ),
			Float.parseFloat( args[ 3 ] ),
			Integer.parseInt( args[ 4 ] ),
			Integer.parseInt( args[ 5 ] ),
			Float.parseFloat( args[ 6 ] ),
			Float.parseFloat( args[ 7 ] ),
			Float.parseFloat( args[ 8 ] )
		);
		
		m.giveCursorMomentum();
	}
	
	public MouseMomentum(
		float decay,
		int interval,
		float momentumThreshold,
		float activeDecay,
		int bounceThreshold,
		int bounceConfidenceRequirement,
		float sensitivity,
		float minVelocity,
		float maxVelocity
	) throws Exception {

		DECAY = decay;
		INTERVAL = interval;
		MOMENTUM_THRESHOLD = momentumThreshold;
		ACTIVE_DECAY = activeDecay;
		BOUNCE_THRESHOLD = bounceThreshold;
		BOUNCE_CONFIDENCE_REQUIREMENT = bounceConfidenceRequirement;
		SENSITIVITY_MODIFIER = 1 - sensitivity;
		MIN_VELOCITY = minVelocity;
		MAX_VELOCITY = maxVelocity;

		System.out.println( "decay: " + decay );
		System.out.println( "interval: " + interval );
		System.out.println( "momentum threshold velocity: " + momentumThreshold );
		System.out.println( "active decay: " + activeDecay );
		System.out.println( "bounce threshold velocity: " + bounceThreshold );
		System.out.println( "bounce confidence requirement: " + bounceConfidenceRequirement );
		System.out.println( "sensitivity: " + sensitivity );
		System.out.println( "min velocity: " + minVelocity );
		System.out.println( "max velocity: " + maxVelocity + (maxVelocity == 0 ? " (max disabled)" : "") );

		robot = new Robot();
	}
	
	public void giveCursorMomentum() throws Exception {

		lastPos = getPointerPosition();
		differenceHistory = new History<>( BOUNCE_CONFIDENCE_REQUIREMENT - 1, () -> new Point( 0, 0 ) );
		appliedVelocityHistory = new History<>( 1, () -> new Point( 0, 0 ) );
		velocityHistory = new History<>( BOUNCE_CONFIDENCE_REQUIREMENT + 1, () -> new Vector( 0f, 0f ) );
		velocityScraps = new Vector( 0f, 0f );
		
		while ( true ) {
			stepCursorWithMomentum();
			Thread.sleep( INTERVAL );
		}
	}
	
	private void stepCursorWithMomentum() {
		
		Point currentPos = getPointerPosition();
		Point difference = Point.difference( lastPos, currentPos );
		Vector velocity = new Vector( velocityHistory.get( 0 ) );
		Point manipulatedAmount = Point.difference( appliedVelocityHistory.get(0), difference );

		System.out.println(currentPos + " | " + difference + " | " + velocity + " | " + manipulatedAmount);

		if ( !manipulatedAmount.isZero() && manipulatedAmount.getMagnitude() >= MOMENTUM_THRESHOLD ) {
			velocity.set(
				difference.getX() - (SENSITIVITY_MODIFIER * manipulatedAmount.getX()),
				difference.getY() - (SENSITIVITY_MODIFIER * manipulatedAmount.getY())
			);
		}
		
		if ( manipulatedAmount.getMagnitude() != 0 && difference.getMagnitude() < MOMENTUM_THRESHOLD ) {
			velocity.scale( (float) Math.pow( DECAY, ACTIVE_DECAY ) );
		} else {
			velocity.scale( DECAY );
		}

		if ( velocity.getMagnitude() < MIN_VELOCITY ) velocity.scale( 0 );

		if ( MAX_VELOCITY != 0 && velocity.getMagnitude() > MAX_VELOCITY ) velocity.scale( MAX_VELOCITY / velocity.getMagnitude() );

		boolean bounceX = bounce( Point::getX, Vector::getX, Vector::setX, difference, velocity );
		boolean bounceY = bounce( Point::getY, Vector::getY, Vector::setY, difference, velocity );
		if ( bounceX || bounceY ) System.out.printf( "bounce. speed: %.2f\n", velocityHistory.get( BOUNCE_CONFIDENCE_REQUIREMENT ).getMagnitude() );

		Point appliedVelocity = new Point(
			roundTowardsZero( velocity.getX() ),
			roundTowardsZero( velocity.getY() )
		);
		
		velocityScraps.add( new Vector( velocity.getX() - appliedVelocity.getX(), velocity.getY() - appliedVelocity.getY() ) );
		applyVelcityScraps( Vector::getX, Vector::setX, Point::addToX, appliedVelocity, velocityScraps );
		applyVelcityScraps( Vector::getY, Vector::setY, Point::addToY, appliedVelocity, velocityScraps );

		applyVelocity( currentPos, appliedVelocity );

		lastPos = currentPos;
		differenceHistory.push( difference );
		appliedVelocityHistory.push( appliedVelocity );
		velocityHistory.push( velocity );
	}

	private boolean bounce(
		Function<Point, Integer> getPointCoord,
		Function<Vector, Float> getVectorCoord,
		BiConsumer<Vector, Float> setVectorCoord,
		Point difference,
		Vector velocity
	) {
		List<Point> differences = new ArrayList<>( differenceHistory.getHistory() );
		differences.add( 0, difference );

		for ( int i = 0; i < BOUNCE_CONFIDENCE_REQUIREMENT; i++ )
			if ( getPointCoord.apply( differences.get( i ) ) != 0 ) return false;

		if ( Math.abs( getVectorCoord.apply( velocityHistory.get( BOUNCE_CONFIDENCE_REQUIREMENT ) ) ) >= BOUNCE_THRESHOLD ) {
			setVectorCoord.accept( velocity, getVectorCoord.apply( velocityHistory.get( BOUNCE_CONFIDENCE_REQUIREMENT ) ) * -1 );
			return true;
		} else {
			return false;
		}
	}

	private void applyVelcityScraps(
		Function<Vector, Float> getVectorCoord,
		BiConsumer<Vector, Float> setVectorCoord,
		BiConsumer<Point, Integer> addToPointCoord,
		Point appliedVelocity,
		Vector velocityScraps
	) {
		float vectorCoord = getVectorCoord.apply( velocityScraps );
		float magnitude = Math.abs( vectorCoord );
		if ( magnitude > 1 ) {
			float sign = Math.signum( vectorCoord );
			addToPointCoord.accept( appliedVelocity, (int) (1 * sign) );
			setVectorCoord.accept( velocityScraps, (magnitude % 1f) * sign );
		}
	}
	
	private void applyVelocity( Point currentPos, Point velocity ) {
		robot.mouseMove( 
			currentPos.getX() + velocity.getX(),
			currentPos.getY() + velocity.getY()
		);
	}

	private static Point getPointerPosition() {
		java.awt.Point pointerLocation = MouseInfo.getPointerInfo().getLocation();
		return new Point( (int) pointerLocation.getX(), (int) pointerLocation.getY() );
	}
	
	private static int roundTowardsZero( float f ) {
		if ( f > 0 )
			return (int) Math.floor( f );
		else
			return (int) Math.ceil( f );
	}
	
	private static class History<T> {
		
		private final int LENGTH;
		
		private List<T> history;
		
		public History( int length, Supplier<T> fill ) {
			LENGTH = length;
			history = new ArrayList<>( length + 1 );
			for ( int i = 0; i < length; i++ ) push( fill.get() );
		}
		
		public void push( T element ) {
			history.add( 0, element );
			if ( history.size() > LENGTH ) history.remove( history.size() - 1 );
		}
		
		public T get( int i ) {
			return history.get( i );
		}

		public List<T> getHistory() {
			return history;
		}

		@Override
		public String toString() {
			return history.toString();
		}
	}

	private static class Point {

		private int x;
		private int y;

		public Point( int x, int y ) {
			this.x = x;
			this.y = y;
		}

		public int getX() {
			return x;
		}

		public int getY() {
			return y;
		}

		public float getMagnitude() {
			return (float) Math.sqrt( (x * x) + (y * y) );
		}

		public void addToX( int add ) {
			x += add;
		}

		public void addToY( int add ) {
			y += add;
		}

		public boolean isZero() {
			return x == 0 && y == 0;
		}

		public static Point difference( Point from, Point to ) {
			return new Point( to.x - from.x, to.y - from.y );
		}

		@Override
		public boolean equals( Object object ) {
			if ( this == object ) return true;
			if ( object == null || getClass() != object.getClass() ) return false;
			if ( !super.equals( object ) ) return false;
			Point point = (Point) object;
			return x == point.x &&
					y == point.y;
		}

		@Override
		public int hashCode() {
			return Objects.hash( super.hashCode(), x, y );
		}

		@Override
		public String toString() {
			return x + " " + y;
		}
	}

	private static class Vector {
		
		private float x;
		private float y;
		
		public Vector( float x, float y ) {
			this.x = x;
			this.y = y;
		}
		
		public Vector( Vector other ) {
			this.x = other.x;
			this.y = other.y;
		}
		
		public Vector() {
			this( 0f, 0f );
		}
		
		public void setX( float x ) {
			this.x = x;
		}
		
		public void setY( float y ) {
			this.y = y;
		}
		
		public void set( float x, float y ) {
			this.x = x;
			this.y = y;
		}
		
		public void scale( float s ) {
			x *= s;
			y *= s;
		}

		public void add( Vector add ) {
			x += add.x;
			y += add.y;
		}
		
		public float getX() {
			return x;
		}
		
		public float getY() {
			return y;
		}

		public float getMagnitude() {
			return (float) Math.sqrt( (x * x) + (y * y) );
		}

		public static Vector add( Vector one, Vector two ) {
			return new Vector( one.getX() + two.getX(), one.getY() + two.getY() );
		}

		@Override
		public String toString() {
			return x + " " + y;
		}
	}
}