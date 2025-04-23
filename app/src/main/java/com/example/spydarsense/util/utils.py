import os
import numpy as np
import pandas as pd
from sklearn.decomposition import PCA
import matplotlib.pyplot as plt

def align_csi_magnitude(csi_data, csi_timestamps, time_interval=0.1, n_subcarriers=None, aggregation_method="mean"):
    """
    Aligns raw CSI magnitude data on a uniform timeline at the specified interval.
    Ensures that the entire timeline from the minimum to the maximum timestamp is included,
    even for timestamps with no data.
    
    Additionally, optionally only keeps a subset of subcarriers.
    
    Parameters:
    -----------
    csi_data : np.ndarray
        CSI magnitude array of shape (n_packets, n_subcarriers_total).
    csi_timestamps : np.ndarray
        Array of packet timestamps (in seconds) of length n_packets.
    time_interval : float
        The interval (in seconds) to round timestamps to.
    n_subcarriers : int, optional
        The number of subcarriers to keep. If None, keep all subcarriers.
        If provided and less than the total, evenly selected subcarriers are used.
    aggregation_method : str, optional
        Aggregation method for packets falling in the same timestamp: 
        "mean" (averages values) or "first" (uses the first packet's value).
    
    Returns:
    --------
    csi_aligned : pd.DataFrame
        DataFrame with columns:
         - 'timestamp': All timestamps from the minimum to maximum (at time_interval steps).
         - 'subcarrier_0', 'subcarrier_1', ... : Aggregated CSI values for each selected subcarrier.
           Timestamps with no data will have NaN values.
    """
    # Determine total number of subcarriers
    total_subcarriers = csi_data.shape[1]
    if n_subcarriers is not None and n_subcarriers < total_subcarriers:
        # Choose evenly spaced indices from 0 to total_subcarriers-1
        indices = np.linspace(0, total_subcarriers - 1, n_subcarriers, dtype=int)
        csi_data = csi_data[:, indices]
    else:
        n_subcarriers = total_subcarriers  # Use all subcarriers

    # Round timestamps to the nearest time_interval
    rounded_times = np.round(csi_timestamps / time_interval) * time_interval

    # Create a DataFrame with subcarrier columns and a timestamp column
    df_csi = pd.DataFrame(csi_data, columns=[f"subcarrier_{i}" for i in range(n_subcarriers)])
    df_csi["timestamp"] = rounded_times

    # Aggregate packets falling in the same timestamp
    if aggregation_method == "mean":
        grouped = df_csi.groupby("timestamp").mean()
    elif aggregation_method == "first":
        grouped = df_csi.groupby("timestamp").first()
    else:
        raise ValueError("aggregation_method must be either 'mean' or 'first'")

    # Create a complete timeline from min to max timestamp
    full_timeline = np.arange(rounded_times.min(), rounded_times.max() + time_interval, time_interval)

    # Reindex the grouped DataFrame to include every timestamp in the timeline
    csi_aligned = grouped.reindex(full_timeline)
    csi_aligned.index.name = "timestamp"
    csi_aligned = csi_aligned.reset_index()

    return csi_aligned

def calculate_bitrate_and_align(bitrate_df, time_interval=0.1, header_adjust=34):
    """
    Calculates bitrate from packet lengths, aligns it on a complete timeline, and returns a DataFrame 
    with 'timestamp' and 'bitrate_bytes'. Missing timestamps will have NaN values.

    Parameters:
    -----------
    bitrate_df : pd.DataFrame
        DataFrame containing:
         - 'frame.time': Timestamps of packets (in seconds).
         - '_ws.col.Length\r': Packet lengths (bytes).
    time_interval : float
        The interval (in seconds) to round timestamps to.
    header_adjust : int
        Value to subtract from each packet’s length.

    Returns:
    --------
    bitrate_aligned : pd.DataFrame
        DataFrame with:
         - 'timestamp': Complete timeline of rounded timestamps.
         - 'bitrate_bytes': Aggregated (summed) payload bytes for each timestamp.
    """
    # Adjust packet lengths
    bitrate_df["_ws.col.Length\r"] = bitrate_df["_ws.col.Length\r"] - header_adjust

    # Round timestamps and add as a new column
    rounded_times = np.round(bitrate_df["frame.time"] / time_interval) * time_interval
    bitrate_df["timestamp"] = rounded_times

    # Group by timestamp and sum payload bytes
    bitrate_grouped = bitrate_df.groupby("timestamp")["_ws.col.Length\r"].sum().reset_index()
    bitrate_grouped.rename(columns={"_ws.col.Length\r": "bitrate_bytes"}, inplace=True)

    # Create a complete timeline from min to max timestamp
    full_timeline = np.arange(rounded_times.min(), rounded_times.max() + time_interval, time_interval)
    bitrate_aligned = bitrate_grouped.set_index("timestamp").reindex(full_timeline).reset_index()
    bitrate_aligned.rename(columns={"index": "timestamp"}, inplace=True)

    return bitrate_aligned

def csi_feature_extraction(csi_aligned, window_size=10, stride=1):
    """
    Extracts a PCA-based feature from aligned CSI data.
    For each sliding window of rows in the DataFrame, computes the variance explained by the first principal component,
    scaled by 8e5.

    Parameters:
    -----------
    csi_aligned : pd.DataFrame
        DataFrame containing:
         - 'timestamp'
         - 'subcarrier_0', 'subcarrier_1', ... (CSI magnitude columns)
    window_size : int
        Number of consecutive rows (timestamps) in each window.
    stride : int
        Step size to slide the window.

    Returns:
    --------
    feature_df : pd.DataFrame
        DataFrame with:
         - 'timestamp': Center timestamp of each window.
         - 'csi_feature': PCA feature (variance explained by the first principal component, scaled by 8e5).
    """
    subcarrier_cols = [col for col in csi_aligned.columns if col.startswith("subcarrier_")]
    csi_matrix = csi_aligned[subcarrier_cols].values

    n_packets = csi_matrix.shape[0]
    pca = PCA(n_components=1)
    feature_list = []
    timestamps_list = []

    for start in range(0, n_packets - window_size + 1, stride):
        window = csi_matrix[start:start + window_size, :]
        pca.fit(window)
        feature_list.append(pca.explained_variance_[0])
        # Use the center timestamp of the window
        center_idx = start + window_size // 2
        timestamps_list.append(csi_aligned.loc[center_idx, "timestamp"])

    feature_array = np.array(feature_list)
    feature_array = np.round(feature_array, 2)
    feature_df = pd.DataFrame({"timestamp": timestamps_list, "csi_feature": feature_array})
    return feature_df

def median_filter_bitrate(bitrate_aligned, window_size=5, stride=1):
    """
    Applies a sliding median filter to the aligned bitrate data.

    Parameters:
    -----------
    bitrate_aligned : pd.DataFrame
        DataFrame with:
         - 'timestamp'
         - 'bitrate_bytes'
    window_size : int
        Number of consecutive points in each sliding window.
    stride : int
        Step size for the sliding window.

    Returns:
    --------
    filtered_df : pd.DataFrame
        DataFrame with:
         - 'timestamp': Center timestamp of each window.
         - 'bitrate_median': Median bitrate value within the window.
    """
    bitrate_sorted = bitrate_aligned.sort_values("timestamp").reset_index(drop=True)
    n = len(bitrate_sorted)
    timestamps_list = []
    medians_list = []

    for start in range(0, n - window_size + 1, stride):
        window = bitrate_sorted.iloc[start:start + window_size]
        medians_list.append(window["bitrate_bytes"].median())
        center_idx = start + window_size // 2
        timestamps_list.append(bitrate_sorted.loc[center_idx, "timestamp"])

    filtered_df = pd.DataFrame({"timestamp": timestamps_list, "bitrate_median": medians_list})
    return filtered_df

def fill_missing_csi(csi_aligned):
    """
    Fills missing CSI values by first forward-filling and then backward-filling.
    Assumes subcarrier columns start with "subcarrier_".

    Parameters:
    -----------
    csi_aligned : pd.DataFrame
        DataFrame with columns:
         - 'timestamp'
         - 'subcarrier_0', 'subcarrier_1', ...
         (may contain NaN values for timestamps with no data)

    Returns:
    --------
    csi_filled : pd.DataFrame
        The DataFrame with missing values filled.
    """
    csi_sorted = csi_aligned.sort_values("timestamp").reset_index(drop=True)
    subcarrier_cols = [col for col in csi_sorted.columns if col.startswith("subcarrier_")]
    csi_sorted[subcarrier_cols] = csi_sorted[subcarrier_cols].ffill().bfill()
    return csi_sorted

def fill_missing_bitrate(bitrate_aligned):
    """
    Fills missing bitrate values with zero.
    Assumes the DataFrame has columns such as 'bitrate_bytes' or 'bitrate_median'.

    Parameters:
    -----------
    bitrate_aligned : pd.DataFrame
        DataFrame with columns:
         - 'timestamp'
         - 'bitrate_bytes' or 'bitrate_median'

    Returns:
    --------
    bitrate_filled : pd.DataFrame
        The DataFrame with missing bitrate values replaced by zero.
    """
    bitrate_sorted = bitrate_aligned.sort_values("timestamp").reset_index(drop=True)
    bitrate_cols = [col for col in bitrate_sorted.columns if "bitrate" in col]
    for col in bitrate_cols:
        bitrate_sorted[col] = bitrate_sorted[col].fillna(0)
    return bitrate_sorted

def join_features(csi_feature_df, bitrate_filtered_df, how="outer"):
    """
    Joins the CSI feature DataFrame and the filtered bitrate DataFrame on the 'timestamp' column.
    
    Since the pipeline already creates a complete timeline and fills missing values,
    the resulting joined DataFrame should have no dry periods.
    
    Parameters:
    -----------
    csi_feature_df : pd.DataFrame
        DataFrame with columns 'timestamp' and 'csi_feature'.
    bitrate_filtered_df : pd.DataFrame
        DataFrame with columns 'timestamp' and 'bitrate_median'.
    how : str, optional
        Type of join to perform ('inner', 'outer', etc.). Default is 'outer'.
    
    Returns:
    --------
    merged_df : pd.DataFrame
        DataFrame with columns 'timestamp', 'csi_feature', and 'bitrate_median'.
    """
    merged_df = pd.merge(csi_feature_df, bitrate_filtered_df, on="timestamp", how=how)
    merged_df = merged_df.sort_values("timestamp").reset_index(drop=True)
    return merged_df

# ----------------------- New Main Functionality -----------------------

def process_file_set(csi_mag_filepath, csi_meta_filepath, br_filepath):
    # Load CSI Magnitude Data (assumes no header)
    try:
        csi_data = pd.read_csv(csi_mag_filepath, header=None).values
    except Exception as e:
        print(f"Error reading CSI magnitude file {csi_mag_filepath}: {e}")
        return

    # Load CSI Metadata (must contain "frame.time")
    try:
        csi_meta_df = pd.read_csv(csi_meta_filepath)
        csi_timestamps = csi_meta_df["frame.time"].values.astype(float)
    except Exception as e:
        print(f"Error reading CSI metadata file {csi_meta_filepath}: {e}")
        return

    # Load Bitrate Data (must contain "frame.time" and "_ws.col.Length\r")
    try:
        br_df = pd.read_csv(br_filepath)
    except Exception as e:
        print(f"Error reading bitrate metadata file {br_filepath}: {e}")
        return

    # --- Process Data ---
    csi_aligned = align_csi_magnitude(csi_data, csi_timestamps, time_interval=0.1, n_subcarriers=12, aggregation_method="mean")
    csi_aligned_filled = fill_missing_csi(csi_aligned)
    bitrate_aligned = calculate_bitrate_and_align(br_df, time_interval=0.1, header_adjust=34)
    bitrate_aligned_filled = fill_missing_bitrate(bitrate_aligned)
    csi_feature_df = csi_feature_extraction(csi_aligned_filled, window_size=10, stride=1)
    bitrate_filtered_df = median_filter_bitrate(bitrate_aligned_filled, window_size=3, stride=1)
    print(csi_feature_df.head())
    print(bitrate_filtered_df.head())
    joined_features = join_features(csi_feature_df, bitrate_filtered_df, how="inner")

    # --- Plot Results ---
    fig, axs = plt.subplots(2, 1, figsize=(12, 8), sharex=True)
    axs[0].plot(joined_features["timestamp"], joined_features["csi_feature"], "b.-", label="CSI Feature")
    axs[0].set_title("Extracted CSI Feature (PCA-based)")
    axs[0].set_ylabel("CSI Feature Value")
    axs[0].legend(loc="upper right")
    axs[0].grid(True)
    # axs[0].set_ylim(0, 1)
    axs[1].plot(joined_features["timestamp"], joined_features["bitrate_median"], "r.-", label="Bitrate")
    axs[1].set_title("Aligned Bitrate Data")
    axs[1].set_xlabel("Timestamp (s)")
    axs[1].set_ylabel("Bitrate (bytes)")
    axs[1].legend(loc="upper right")
    axs[1].grid(True)
    # axs[1].set_ylim(0, 1)
    plt.tight_layout()
    plt.show()

def main():
    # ----- Configuration: Update these variables -----
    base_folder = r"C:\Study\FYP\Data\CCTV\FacultyRoom-Aggressive-cctv (Large)"  # Update this path to your base folder
    t = 5  # Set to 0 to process all file sets; set to a nonzero integer to process that specific file set (1-indexed)
    # --------------------------------------------------

    # Define subfolder paths
    br_folder = os.path.join(base_folder, "br_metadata")
    csi_meta_folder = os.path.join(base_folder, "csi_metadata")
    csi_mag_folder = os.path.join(base_folder, "csi_magnitude_data")

    # Verify that the subfolders exist
    for folder in [br_folder, csi_meta_folder, csi_mag_folder]:
        if not os.path.isdir(folder):
            raise FileNotFoundError(f"Subfolder {folder} does not exist.")

    # List and sort files in each subfolder
    br_files = sorted([f for f in os.listdir(br_folder) if os.path.isfile(os.path.join(br_folder, f))])
    csi_meta_files = sorted([f for f in os.listdir(csi_meta_folder) if os.path.isfile(os.path.join(csi_meta_folder, f))])
    csi_mag_files = sorted([f for f in os.listdir(csi_mag_folder) if os.path.isfile(os.path.join(csi_mag_folder, f))])

    # Determine the number of sets to process
    num_sets = min(len(br_files), len(csi_meta_files), len(csi_mag_files))
    if num_sets == 0:
        print("No files found in one or more subfolders.")
        return

    # If t > 0, process only that set (t is 1-indexed for human readability)
    if t > 0:
        if t > num_sets:
            raise IndexError(f"t is set to {t} but there are only {num_sets} file sets available.")
        indices = [t - 1]
    else:
        indices = list(range(num_sets))

    # Process each set based on selected indices
    for i in indices:
        br_file = br_files[i]
        csi_meta_file = csi_meta_files[i]
        csi_mag_file = csi_mag_files[i]

        # Verify that the files correspond by comparing characters 3 to 17 of their filenames
        seg_br = br_file[3:18]
        seg_csi_meta = csi_meta_file[3:18]
        seg_csi_mag = csi_mag_file[3:18]
        if seg_br == seg_csi_meta == seg_csi_mag:
            print(f"Processing file set {i+1}:")
            print(f"  br_metadata: {br_file}")
            print(f"  csi_metadata: {csi_meta_file}")
            print(f"  csi_magnitude_data: {csi_mag_file}")
        else:
            print(f"Skipping file set {i+1} due to filename mismatch:")
            print(f"  br_metadata segment: {seg_br}")
            print(f"  csi_metadata segment: {seg_csi_meta}")
            print(f"  csi_magnitude_data segment: {seg_csi_mag}")
            continue

        # Construct full file paths
        br_filepath = os.path.join(br_folder, br_file)
        csi_meta_filepath = os.path.join(csi_meta_folder, csi_meta_file)
        csi_mag_filepath = os.path.join(csi_mag_folder, csi_mag_file)

        # Process and plot this set of files
        process_file_set(csi_mag_filepath, csi_meta_filepath, br_filepath)

if __name__ == "__main__":
    main()