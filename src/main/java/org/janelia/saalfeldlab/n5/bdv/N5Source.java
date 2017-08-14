package org.janelia.saalfeldlab.n5.bdv;

import java.io.IOException;

import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import bdv.tools.transformation.TransformedSource;
import bdv.util.RandomAccessibleIntervalMipmapSource;
import bdv.util.VolatileRandomAccessibleIntervalMipmapSource;
import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileTypeMatcher;
import bdv.viewer.Source;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Volatile;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.util.Util;

public class N5Source
{
	@SuppressWarnings("unchecked")
	public static < T extends NumericType< T > & NativeType< T >, V extends Volatile< T > & NumericType< V > > Source< V > getVolatileSource(
			final N5Reader n5,
			final N5ExportMetadata metadata,
			final int channel,
			final String name,
			final SharedQueue queue ) throws IOException
	{
		final RandomAccessibleIntervalMipmapSource< T > source = getRandomAccessibleIntervalMipmapSource( n5, metadata, channel, name );
		final VolatileRandomAccessibleIntervalMipmapSource< T, V > volatileSource = source.asVolatile( ( V ) VolatileTypeMatcher.getVolatileTypeForType( source.getType() ), queue );
		final Source< V > transformedVolatileSource = applyTransform( volatileSource, metadata, channel );
		return transformedVolatileSource;
	}

	public static < T extends NumericType< T > & NativeType< T > > Source< T > getSource(
			final N5Reader n5,
			final N5ExportMetadata metadata,
			final int channel,
			final String name ) throws IOException
	{
		final RandomAccessibleIntervalMipmapSource< T > source = getRandomAccessibleIntervalMipmapSource( n5, metadata, channel, name );
		final Source< T > transformedSource = applyTransform( source, metadata, channel );
		return transformedSource;
	}

	@SuppressWarnings("unchecked")
	private static < T extends NumericType< T > & NativeType< T > > RandomAccessibleIntervalMipmapSource< T > getRandomAccessibleIntervalMipmapSource(
			final N5Reader n5,
			final N5ExportMetadata metadata,
			final int channel,
			final String name ) throws IOException
	{
		final double[][] scales = metadata.getScales( channel );
		final RandomAccessibleInterval< T >[] scaleLevelImgs = new RandomAccessibleInterval[ scales.length ];
		for ( int s = 0; s < scales.length; ++s )
			scaleLevelImgs[ s ] = N5Utils.openVolatile( n5, N5ExportMetadata.getScaleLevelDatasetPath( channel, s ) );

		final RandomAccessibleIntervalMipmapSource< T > source = new RandomAccessibleIntervalMipmapSource<>(
				scaleLevelImgs,
				Util.getTypeFromInterval( scaleLevelImgs[ 0 ] ),
				scales,
				metadata.getPixelResolution( channel ),
				name );

		return source;
	}

	private static < T > Source< T > applyTransform(
			final Source< T > source,
			final N5ExportMetadata metadata,
			final int channel ) throws IOException
	{
		final TransformedSource< T > transformedSource = new TransformedSource<>( source );

		// account for the pixel resolution
		if ( source.getVoxelDimensions() != null )
		{
			final AffineTransform3D voxelSizeTransform = new AffineTransform3D();
			final double[] normalizedVoxelSize = getNormalizedVoxelSize( source.getVoxelDimensions() );
			for ( int d = 0; d < voxelSizeTransform.numDimensions(); ++d )
				voxelSizeTransform.set( normalizedVoxelSize[ d ], d, d );
			transformedSource.setFixedTransform( voxelSizeTransform );
		}

		// prepend with the source transform
		final AffineTransform3D metadataTransform = metadata.getAffineTransform( channel );
		if ( metadataTransform != null )
			transformedSource.setIncrementalTransform( metadataTransform );

		return transformedSource;
	}

	private static double[] getNormalizedVoxelSize( final VoxelDimensions voxelDimensions )
	{
		double minVoxelDim = Double.POSITIVE_INFINITY;
		for ( int d = 0; d < voxelDimensions.numDimensions(); ++d )
			minVoxelDim = Math.min( minVoxelDim, voxelDimensions.dimension( d ) );
		final double[] normalizedVoxelSize = new double[ voxelDimensions.numDimensions() ];
		for ( int d = 0; d < voxelDimensions.numDimensions(); ++d )
			normalizedVoxelSize[ d ] = voxelDimensions.dimension( d ) / minVoxelDim;
		return normalizedVoxelSize;
	}
}