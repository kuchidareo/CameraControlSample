### Camera Control with Camera2 API 

- Env. 
    - Pixel 8
    - Compile Sdk 33
    - Camera2 API 1.2.1

- Note
    - Pixel 8 has 2 cameraIds (0, 1)
        - The camera with id=0 is back side. it has 3 physicalCameraIds (2, 3, 4) which correspond to (wide, superwide, telephoto)
        - The camera with id=1 is front side. it has 2 physicalCameraIds (5, 6)
    - cameraManager.openCamera with cameraId, then set CameraDevice.physicalCameraId with physicalCameraId.
        - e.g. When we want to open superwide camera
            - openCamera with id=0, set physicalCameraId with id=2
    - In this sample code, compare focalLength and sensorSize. Assume the wide camrea has largest sensorSize. Then get superwide and telephoto camreas by comparing the focalLength. 
    
